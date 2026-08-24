package com.jeevavibeapp.spendwise.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Merchant resolution, confidence scoring and learning. Pure Kotlin.
 *
 * Confidence adds to 100:
 *   past mapping 40, amount pattern 20, category pattern 15,
 *   correction history 15, time pattern 10.
 *
 * Nothing here touches a database. The caller supplies the learning rows and
 * decides what to persist, which is what lets the whole scoring model be
 * tested on a JVM in milliseconds instead of through an emulator.
 */
object Engine {

    const val W_PAST_MAPPING = 40.0
    const val W_AMOUNT = 20.0
    const val W_CATEGORY = 15.0
    const val W_CORRECTION = 15.0
    const val W_TIME = 10.0
    private const val FULL_TRUST = 5.0

    const val DECISION_AUTO = "auto_saved"
    const val DECISION_CONFIRM = "confirmation_required"
    const val DECISION_MANUAL = "manual_required"

    const val SEED_CONFIDENCE = 90

    private val NOISE_TOKENS = setOf(
        "UPI", "VPA", "P2M", "P2A", "POS", "NEFT", "IMPS", "RTGS", "ACH",
        "PVT", "LTD", "LIMITED", "PRIVATE", "AND", "THE",
        "PAYMENTS", "PAYMENT", "INDIA", "ONLINE", "RETAIL", "STORES", "STORE")

    private val NOISE = Regex("[^A-Z0-9& ]+")
    private val LONG_DIGITS = Regex("\\b\\d{4,}\\b")
    private val GLUED_DIGITS = Regex("(?<=[A-Z])\\d{1,3}\\b")

    /** Fold every spelling of a merchant onto one key, so all of a merchant's
     *  handle variants share the learning the user did once. */
    fun normalizeMerchant(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.uppercase().trim().substringBefore('@')
        s = s.replace('/', ' ').replace('-', ' ').replace('_', ' ').replace('.', ' ')
        s = LONG_DIGITS.replace(s, " ")
        // VPA-style digit suffixes glued to the name: SWIGGY8 -> SWIGGY.
        s = GLUED_DIGITS.replace(s, "")
        s = NOISE.replace(s, " ")
        val tokens = s.split(" ").filter { it.isNotEmpty() && it !in NOISE_TOKENS }
            .toMutableList()
        // Trailing pure-digit tokens are references, not names.
        while (tokens.isNotEmpty() && tokens.last().all { it.isDigit() }) {
            tokens.removeAt(tokens.size - 1)
        }
        return tokens.joinToString(" ").trim()
    }

    /** Built-in Indian merchants, so a fresh install recognises the obvious
     *  ones before any learning exists. */
    val SEED_MERCHANTS: Map<String, Pair<String, String>> = mapOf(
        "SWIGGY" to ("Swiggy" to "Food & Dining"),
        "ZOMATO" to ("Zomato" to "Food & Dining"),
        "DOMINOS" to ("Dominos" to "Food & Dining"),
        "KFC" to ("KFC" to "Food & Dining"),
        "MCDONALD" to ("McDonalds" to "Food & Dining"),
        "MCDONALDS" to ("McDonalds" to "Food & Dining"),
        "STARBUCKS" to ("Starbucks" to "Food & Dining"),
        "PIZZAHUT" to ("Pizza Hut" to "Food & Dining"),
        "BLINKIT" to ("Blinkit" to "Groceries"),
        "ZEPTO" to ("Zepto" to "Groceries"),
        "BIGBASKET" to ("BigBasket" to "Groceries"),
        "DMART" to ("DMart" to "Groceries"),
        "JIOMART" to ("JioMart" to "Groceries"),
        "INSTAMART" to ("Swiggy Instamart" to "Groceries"),
        "AMAZON" to ("Amazon" to "Shopping"),
        "FLIPKART" to ("Flipkart" to "Shopping"),
        "MYNTRA" to ("Myntra" to "Shopping"),
        "AJIO" to ("Ajio" to "Shopping"),
        "MEESHO" to ("Meesho" to "Shopping"),
        "NYKAA" to ("Nykaa" to "Shopping"),
        "UBER" to ("Uber" to "Transport"),
        "OLA" to ("Ola" to "Transport"),
        "RAPIDO" to ("Rapido" to "Transport"),
        "IRCTC" to ("IRCTC" to "Transport"),
        "REDBUS" to ("RedBus" to "Transport"),
        "INDIGO" to ("IndiGo" to "Transport"),
        "JIO" to ("Jio" to "Bills & Utilities"),
        "AIRTEL" to ("Airtel" to "Bills & Utilities"),
        "VODAFONE" to ("Vi" to "Bills & Utilities"),
        "BSNL" to ("BSNL" to "Bills & Utilities"),
        "TATAPOWER" to ("Tata Power" to "Bills & Utilities"),
        "BESCOM" to ("BESCOM" to "Bills & Utilities"),
        "NETFLIX" to ("Netflix" to "Entertainment"),
        "HOTSTAR" to ("Disney+ Hotstar" to "Entertainment"),
        "SPOTIFY" to ("Spotify" to "Entertainment"),
        "PRIMEVIDEO" to ("Prime Video" to "Entertainment"),
        "BOOKMYSHOW" to ("BookMyShow" to "Entertainment"),
        "SONYLIV" to ("SonyLIV" to "Entertainment"),
        "APOLLO" to ("Apollo Pharmacy" to "Health"),
        "PHARMEASY" to ("PharmEasy" to "Health"),
        "NETMEDS" to ("Netmeds" to "Health"),
        "PRACTO" to ("Practo" to "Health"),
    )

    fun seedLookup(normalized: String): Pair<String, String>? {
        if (normalized.isBlank()) return null
        SEED_MERCHANTS[normalized.replace(" ", "")]?.let { return it }
        return SEED_MERCHANTS[normalized.split(" ").first()]
    }

    private fun scorePast(row: LearningRow): Double {
        val strength = row.confirmationCount + 0.5 * row.sampleCount - row.correctionCount
        return W_PAST_MAPPING * max(0.0, min(1.0, strength / FULL_TRUST))
    }

    private fun scoreAmount(row: LearningRow, amount: Double?): Double {
        if (amount == null || row.sampleCount <= 0) return 0.0
        val tolerance = max(max(row.avgAmount * 0.25, (row.amountMax - row.amountMin) / 2.0), 1.0)
        var closeness = max(0.0, 1.0 - abs(amount - row.avgAmount) / (tolerance * 2.0))
        if (amount in row.amountMin..row.amountMax) closeness = max(closeness, 0.6)
        return W_AMOUNT * closeness
    }

    private fun scoreCategory(row: LearningRow, categoryId: String?): Double {
        if (row.categoryId == null) return 0.0
        if (categoryId == null) return W_CATEGORY * 0.5
        return if (row.categoryId == categoryId) W_CATEGORY else 0.0
    }

    private fun scoreCorrection(row: LearningRow): Double {
        val denom = row.confirmationCount + row.correctionCount + 1
        return W_CORRECTION * ((row.confirmationCount + 1).toDouble() / denom)
    }

    private fun scoreTime(row: LearningRow, occurredAt: LocalDateTime?): Double {
        val hist = row.hourHistogram
        if (occurredAt == null || hist.isEmpty() || hist.sum() <= 0) return 0.0
        val hour = occurredAt.hour % 24
        val peak = max(hist.max(), 1)
        return W_TIME * (if (hour < hist.size) hist[hour].toDouble() / peak else 0.0)
    }

    fun score(row: LearningRow, amount: Double?, categoryId: String?,
              occurredAt: LocalDateTime?): Breakdown {
        val past = scorePast(row)
        val amt = scoreAmount(row, amount)
        val cat = scoreCategory(row, categoryId)
        val corr = scoreCorrection(row)
        val tm = scoreTime(row, occurredAt)
        val total = max(0.0, min(100.0, past + amt + cat + corr + tm)).roundToInt()
        return Breakdown(past, amt, cat, corr, tm, total)
    }

    fun decide(total: Int, auto: Int, confirm: Int): String = when {
        total >= auto -> DECISION_AUTO
        total >= confirm -> DECISION_CONFIRM
        else -> DECISION_MANUAL
    }

    /** Jaccard overlap between token sets, so "SWIGGY INSTAMART" still finds
     *  the learning the user already did on "SWIGGY". */
    fun tokenOverlap(a: String, b: String): Double {
        val ta = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val tb = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        return ta.intersect(tb).size.toDouble() / ta.union(tb).size
    }

    /** Confidence from the mapping alone, with no incoming transaction to
     *  compare against — what the ledger shows for an already-learned payee. */
    fun baselineConfidence(row: LearningRow): Int {
        val past = scorePast(row)
        val correction = scoreCorrection(row)
        val category = if (row.categoryId != null) W_CATEGORY else 0.0
        return max(0.0, min(100.0, past + correction + category)).roundToInt()
    }

    /** Why a prediction scored what it did. A suggestion the user cannot
     *  interrogate is one they will eventually stop trusting. */
    fun explain(b: Breakdown, row: LearningRow?, seeded: Boolean = false): List<String> {
        if (seeded) return listOf("Well-known Indian merchant (built-in)")
        val out = mutableListOf<String>()
        if (row != null) {
            if (row.confirmationCount > 0)
                out += "You confirmed this payee ${row.confirmationCount} time" +
                       (if (row.confirmationCount == 1) "" else "s")
            if (row.correctionCount > 0)
                out += "Corrected ${row.correctionCount} time" +
                       (if (row.correctionCount == 1) "" else "s")
        }
        if (b.amountPattern >= W_AMOUNT * 0.5) out += "The amount matches what you usually pay here"
        if (b.categoryPattern >= W_CATEGORY) out += "Same category as before"
        if (b.timePattern >= W_TIME * 0.5) out += "You usually pay here around this time"
        if (out.isEmpty()) out += "No history for this payee yet"
        return out
    }
}

/** One learned raw-name -> merchant mapping. */
data class LearningRow(
    val rawName: String,
    val merchantName: String,
    val categoryId: String? = null,
    val confirmationCount: Int = 0,
    val correctionCount: Int = 0,
    val sampleCount: Int = 0,
    val avgAmount: Double = 0.0,
    val amountMin: Double = 0.0,
    val amountMax: Double = 0.0,
    val hourHistogram: List<Int> = emptyList(),
)

data class Breakdown(
    val pastMapping: Double,
    val amountPattern: Double,
    val categoryPattern: Double,
    val correctionHistory: Double,
    val timePattern: Double,
    val total: Int,
)
