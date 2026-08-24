package com.jeevavibeapp.spendwise.core

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Deterministic SMS / UPI transaction parser. Pure Kotlin, no Android.
 *
 * A direct port of the Python parser this app used to embed, tuned against
 * real Indian bank and UPI formats: HDFC, SBI ("debited by 199.0", no Rs
 * prefix), ICICI ("; SWIGGY credited."), Axis ("UPI/P2M/<ref>/ZOMATO/…"),
 * Kotak ("Sent Rs.20.00 from Kotak Bank AC X1234 to swiggy8@ybl"), PhonePe,
 * GPay, Paytm, card alerts and EMI debits.
 *
 * Deliberately has no dependency on Android, Room or any framework, so it can
 * be compiled and tested on a plain JVM. That is not a stylistic preference:
 * the whole value of this file is the edge cases encoded in it, and those are
 * only worth anything if they can be re-verified cheaply.
 */
object Parsing {

    /** Bumped whenever matching behaviour changes, so a parse miss can be
     *  attributed to the parser that produced it and re-checked after a fix. */
    const val PARSER_VERSION = "2026.1.0-kt"

    /** Upper bound on a single transaction, in rupees. Absurdly generous (one
     *  lakh crore) on purpose — the job is not to second-guess the user, it is
     *  to guarantee the value is finite and survives (amount * 100) without
     *  overflowing, which every downstream money calculation assumes. */
    const val MAX_AMOUNT = 1e12

    private const val D = "$"          // '$' opens a template inside a raw string

    // Indian-script digits seen in regional-language bank SMS. NFKC folds most
    // of them, but these are mapped explicitly so amounts survive.
    private val DIGIT_MAP: Map<Char, Char> = buildMap {
        val scripts = listOf('\u0966', '\u0BE6', '\u0C66')   // Deva, Tamil, Telugu
        for (base in scripts) for (i in 0..9) put(base + i, ('0' + i))
    }

    // Zero-width and directional marks that banks and telcos inject, and that
    // silently break otherwise-correct patterns.
    private val INVISIBLE = Regex("[\u200b-\u200f\u202a-\u202e\u2060\ufeff]")
    private val WS_RUN = Regex("[ \t]+")

    private const val CURRENCY = "(?:rs\\.?|inr|₹)"
    private const val NUM = "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)"

    private val I = setOf(RegexOption.IGNORE_CASE)

    /** Amount with an explicit currency marker: "Rs.450.00", "INR 1,234". */
    private val AMOUNT = Regex("$CURRENCY\\s*$NUM", I)

    /** SBI-style verb-anchored amount with no currency marker:
     *  "A/C X9218 debited by 199.0", "credited with 5,000". */
    private val VERB_AMOUNT = Regex(
        "\\b(?:debited|credited)\\s+(?:by|with|for|of)\\s+(?:$CURRENCY\\s*)?$NUM", I)

    /** Amounts that are a balance, not the transaction: "Avl Bal Rs 12,430.50". */
    private val BALANCE_CTX = Regex(
        "(?:avl|avail(?:able)?|a/c|account|total|closing|updated)\\s*(?:bal(?:ance)?)?\\s*" +
        "(?:bal(?:ance)?)?\\s*(?:is|:|-)?\\s*$D", I)

    private val REF = Regex(
        "(?:ref(?:erence)?(?:\\s*(?:no|number|id))?|txn(?:\\s*id)?|utr|upi\\s*ref)" +
        "[:\\s.#-]*([A-Za-z0-9]{6,})", I)

    private val CREDIT = Regex("\\b(credited|received|deposit(?:ed)?)\\b", I)
    private val DEBIT = Regex("\\b(debited|spent|paid|sent|withdrawn|purchase(?:d)?)\\b", I)

    /** The transactional gate: a money-movement verb must be present, or the
     *  message is promotional or informational and must never be captured. */
    private val TXN_VERB = Regex(
        "\\b(debited|credited|spent|paid|sent|received|withdrawn|purchase(?:d)?|" +
        "deposit(?:ed)?|transferred|payment)\\b", I)

    /** Money that has NOT moved: offers, UPI collect requests, autopay
     *  pre-debit reminders, EMI notices, declined and reversed transactions,
     *  and the marketing / lending / scam vocabulary that carries amounts and
     *  sometimes even transaction verbs. */
    private val NON_TXN = Regex(
        "(\\boff\\b|\\boffer|cashback|discount|coupon|% ?off|flat \\d|" +
        "payment request|requested|collect request|is requesting|" +
        "will be (?:debited|deducted|charged)|due on|due by|overdue|" +
        "e-?mandate|autopay.{0,20}(?:scheduled|upcoming)|" +
        "declined|failed|reversed|refund initiated|otp|one.?time password|" +
        "credit score|cibil|pre-?approved|pre-?qualified|eligible for|" +
        "loan offer|personal loan|instant loan|apply now|click here|" +
        "congratulations|you have won|claim now|lucky (?:winner|draw)|" +
        "limited period|hurry|t&c apply|terms and conditions apply|" +
        "unsubscribe|click .{0,12}(?:link|bit\\.ly|tinyurl)|bit\\.ly|tinyurl|" +
        "verify (?:your )?kyc|kyc (?:update|pending|expired)|will be blocked|" +
        "account will be (?:blocked|suspended|closed)|" +
        "bill (?:is )?generated|statement (?:is )?generated|minimum amount due|" +
        "total amount due|outstanding|emi of|recharge|plan validity|" +
        "interest rate|low interest|no documents|approved)", I)

    /** Positive evidence of a REAL bank/UPI transaction: essentially every
     *  genuine alert cites an account, card, UPI handle or reference.
     *  Promotional and scam messages carry amounts but almost never this, so
     *  requiring it is the highest-precision signal available offline. */
    private val ACCOUNT_EVIDENCE = Regex(
        "(a/c|a/c no|acct|account\\s*(?:no|number|xx|\\*|ending)|" +
        "\\bac\\b\\s*[xX*\\d]|card\\s*(?:no\\.?\\s*)?[xX*\\d]|" +
        "\\bupi\\b|\\bvpa\\b|@[a-z]{2,}|" +
        "ref(?:erence)?\\s*(?:no|number|id)?[:\\s.#-]*[A-Za-z0-9]{6,}|" +
        "\\butr\\b|\\btxn\\b|transaction\\s*id|\\bimps\\b|\\bneft\\b|\\brtgs\\b|" +
        "[xX*]{2,}\\d{3,}|\\d{4,}\\b(?=\\s*(?:on|dated)))", I)

    /** Strings that are obviously not a merchant: bare dates, call-to-action
     *  verbs, and marketing phrases the payee pattern can latch onto. */
    private val BAD_MERCHANT = Regex(
        "^(?:\\d{1,2}[-/][A-Za-z]{3,9}[-/]\\d{2,4}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|" +
        "\\d[\\d,.]*|" +
        "(?:proceed|continue|click|apply|claim|verify|confirm|know more|" +
        "activate|register|download|login|update|check|call|contact|" +
        "low interest|no documents|improve.*|your .*score.*|avail.*|get .*)" +
        ")$D", I)

    // "ref\\w*" rather than a bare "ref": the original stopped only at the exact
    // word "ref", so "trf to SWIGGY Refno 553201998877" yielded the merchant
    // name "SWIGGY Refno 553201998877" — reference digits and all — which then
    // became its own merchant in the ledger and never matched the real SWIGGY.
    private const val BOUNDARY =
        "(?=\\s+(?:on|ref\\w*|txn|utr|upi|avl|a/c|bal|info|via|using|to|not|is)\\b|[.,;]|$D)"

    /** Payee markers for debits. "from" is the payer side and is only trusted
     *  for credits — except as a last resort, because Kotak writes
     *  "Sent Rs.20 from Kotak Bank AC X1234 to swiggy8@ybl". */
    private val TO = Regex(
        "(?:\\bto\\b|\\bat\\b|towards|\\bvpa\\b|info[:\\-])\\s*" +
        "([A-Za-z0-9][A-Za-z0-9 &._@/-]{1,60}?)$BOUNDARY", I)
    private val FROM = Regex(
        "\\bfrom\\b\\s*([A-Za-z0-9][A-Za-z0-9 &._@/-]{1,60}?)$BOUNDARY", I)

    /** Axis card/UPI path: "UPI/P2M/519023481234/ZOMATO/…". */
    private val UPI_PATH = Regex("UPI/(?:P2[MA]/)?\\d{6,}/([A-Za-z0-9 &._-]{2,40})", I)

    /** ICICI style: "…; SWIGGY credited." names the payee before the verb. */
    private val PAYEE_CREDITED = Regex(
        "[;.]\\s*([A-Za-z0-9][A-Za-z0-9 &._-]{1,40}?)\\s+credited", I)

    private val DATE_PATTERNS = listOf(
        // "on date 08Jul26" is ordinary SBI phrasing and the original required
        // the digits to follow "on" immediately, so those messages silently
        // lost their date and were filed under the day they were received.
        Regex("\\bon\\s+(?:date\\s+)?(\\d{1,2}[-/][A-Za-z]{3,9}[-/]\\d{2,4})", I),
        Regex("\\bon\\s+(?:date\\s+)?(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})", I),
        Regex("\\bon\\s+(?:date\\s+)?(\\d{1,2}[A-Za-z]{3}\\d{2,4})", I),   // SBI "08Jul26"
        Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b"),
    )
    private val TIME = Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?)\\b")

    private val MONTHS = listOf(
        "jan", "feb", "mar", "apr", "may", "jun",
        "jul", "aug", "sep", "oct", "nov", "dec")

    /** Canonicalise a message before any pattern is applied.
     *
     * NFKC folds full-width and compatibility forms (ｒｓ, ￥) to ASCII, Indian
     * script digits are mapped, invisible marks are stripped, whitespace runs
     * collapse. Without this an ordinary message can fail to parse for
     * reasons nobody can see by reading it. */
    fun normalizeText(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC)
        if (s.any { it in DIGIT_MAP }) {
            s = s.map { DIGIT_MAP[it] ?: it }.joinToString("")
        }
        s = INVISIBLE.replace(s, "")
        s = s.replace('\u00a0', ' ')
        return WS_RUN.replace(s, " ").trim()
    }

    /** A usable rupee amount, or null if the value is not one.
     *
     * Rejects NaN, both infinities, and anything outside (0, MAX_AMOUNT].
     * This exists because parsing a number accepts far more than money: a
     * 400-digit string becomes Infinity and compares as > 0, so it passed the
     * transaction gate. A stored Infinity then reached the analytics, where
     * (amount * 100) overflowed and permanently broke the dashboard — a
     * denial of service triggerable by anyone who can send the device an SMS. */
    fun safeAmount(value: Any?): Double? {
        val f = when (value) {
            null -> return null
            is Double -> value
            is Number -> value.toDouble()
            is String -> value.trim().replace(",", "").toDoubleOrNull() ?: return null
            else -> return null
        }
        if (f.isNaN() || f.isInfinite()) return null
        if (f <= 0.0 || f > MAX_AMOUNT) return null
        return f
    }

    private fun parseAmount(text: String): Double? {
        // Verb-anchored wins: it is unambiguously the transaction amount.
        VERB_AMOUNT.find(text)?.let { return safeAmount(it.groupValues[1]) }
        // Otherwise the first currency-marked amount that is NOT a balance.
        for (m in AMOUNT.findAll(text)) {
            val ctx = text.substring(maxOf(0, m.range.first - 24), m.range.first)
            if (BALANCE_CTX.containsMatchIn(ctx)) continue
            return safeAmount(m.groupValues[1])
        }
        return null
    }

    private fun parseDate(text: String): LocalDateTime? {
        var raw: String? = null
        for (rx in DATE_PATTERNS) {
            rx.find(text)?.let { raw = it.groupValues[1]; return@let }
            if (raw != null) break
        }
        val value = raw ?: return null
        val date = parseDateParts(value) ?: return null
        var hour = 0
        var minute = 0
        TIME.find(text)?.let {
            val parts = it.groupValues[1].split(":")
            hour = parts[0].toIntOrNull()?.coerceIn(0, 23) ?: 0
            minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        }
        return LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute)
    }

    /** Accepts every shape the bank corpus actually uses: 05-Jan-24,
     *  08/07/2026, 08Jul26, 2026-07-08. Written out rather than handed to a
     *  date library because the two-digit-year and no-separator forms need
     *  rules a formatter cannot express in one pattern. */
    private fun parseDateParts(raw: String): LocalDate? {
        fun year(y: Int) = if (y < 100) 2000 + y else y
        fun month(name: String): Int? {
            val idx = MONTHS.indexOf(name.lowercase().take(3))
            return if (idx >= 0) idx + 1 else null
        }
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(raw)?.let { m ->
            return safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                            m.groupValues[3].toInt())
        }
        Regex("^(\\d{1,2})[-/]([A-Za-z]{3,9})[-/](\\d{2,4})$").find(raw)?.let { m ->
            val mm = month(m.groupValues[2]) ?: return null
            return safeDate(year(m.groupValues[3].toInt()), mm, m.groupValues[1].toInt())
        }
        Regex("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{2,4})$").find(raw)?.let { m ->
            return safeDate(year(m.groupValues[3].toInt()), m.groupValues[2].toInt(),
                            m.groupValues[1].toInt())
        }
        Regex("^(\\d{1,2})([A-Za-z]{3})(\\d{2,4})$").find(raw)?.let { m ->
            val mm = month(m.groupValues[2]) ?: return null
            return safeDate(year(m.groupValues[3].toInt()), mm, m.groupValues[1].toInt())
        }
        return null
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        try { LocalDate.of(y, m, d) } catch (e: Exception) { null }

    private fun cleanMerchant(raw: String): String? {
        var name = raw.trim(' ', '.', ',', '-', '/').substringBefore('@').trim()
        if (name.isEmpty()) return null
        // Reject pure digits and account fragments ("X1234", "919…").
        if (!Regex("[A-Za-z]{2}").containsMatchIn(name)) return null
        if (BAD_MERCHANT.matches(name)) return null
        // A real payee is short. Anything sentence-like is scraped prose.
        if (name.split(Regex("\\s+")).size > 4) return null
        return name
    }

    private fun parseMerchant(text: String, type: String): String? {
        val strategies = if (type == "expense")
            listOf(TO, UPI_PATH, PAYEE_CREDITED, FROM)
        else
            listOf(FROM, TO, UPI_PATH)
        for (rx in strategies) {
            val m = rx.find(text) ?: continue
            cleanMerchant(m.groupValues[1])?.let { return it }
        }
        return null
    }

    /** Parse a finance SMS. Never throws: an unparseable message comes back
     *  with matched = false, which is a normal outcome, not an error. */
    fun parse(text: String?): ParsedSms {
        val normalized = normalizeText(text)
        if (normalized.isEmpty()) return ParsedSms()

        val amount = parseAmount(normalized)
        val type = if (CREDIT.containsMatchIn(normalized) &&
                       !DEBIT.containsMatchIn(normalized)) "income" else "expense"

        // A real transaction needs ALL of: an amount, a money-movement verb,
        // no promo/request/pre-debit language, and positive account evidence.
        // The last condition is what keeps promotional and scam messages —
        // which happily carry amounts and even verbs — out of the ledger.
        val matched = amount != null &&
            amount > 0 &&
            TXN_VERB.containsMatchIn(normalized) &&
            !NON_TXN.containsMatchIn(normalized) &&
            ACCOUNT_EVIDENCE.containsMatchIn(normalized)

        return ParsedSms(
            amount = amount,
            type = type,
            rawMerchant = parseMerchant(normalized, type),
            referenceNumber = REF.find(normalized)?.groupValues?.get(1),
            occurredAt = parseDate(normalized),
            matched = matched,
        )
    }
}

data class ParsedSms(
    val amount: Double? = null,
    val type: String = "expense",
    val rawMerchant: String? = null,
    val referenceNumber: String? = null,
    val occurredAt: LocalDateTime? = null,
    val matched: Boolean = false,
)
