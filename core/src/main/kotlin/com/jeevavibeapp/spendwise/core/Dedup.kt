package com.jeevavibeapp.spendwise.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * When two bank messages are one payment.
 *
 * Several banks send both an SMS and a UPI confirmation for one payment, the
 * same SMS can reach the app twice (a network re-delivery, or the live
 * receiver and the launch inbox scan both reading it), and every launch
 * rescans ninety days of inbox. Each of those must land once. Two genuinely
 * different payments must not: the parser-2026.1.0-kt key,
 * amount|payee|reference|day, filed two ₹500 Amazon debits on one day as one
 * row, and a debit and a credit of the same amount and payee as one row.
 *
 * So a message is identified by:
 *  - its reference, scoped by direction and account, when it has one. The
 *    reference is the bank's own name for the payment, and two rails
 *    describing one payment carry the same one;
 *  - otherwise the message itself: who sent it, when the phone received it
 *    (to within [WINDOW_MINUTES]) and its text. A paste has neither sender nor
 *    timestamp, so it is its text and the day it is filed under.
 *
 * Phones already hold rows stored under the old key, so every identity also
 * answers to it ([legacyKey]). A message is already banked when ANY key in
 * [Identity.all] is in the ledger; a new row stores [Identity.key].
 *
 * The encodings below are part of the stored keys and so can never change:
 *  - text: lowercase hex SHA-256 of the UTF-8 bytes of [Parsing.normalizeText]
 *    of the body — the parser's canonical form, so an invisible mark or a
 *    re-wrapped space does not make a second message;
 *  - amount: rupees with exactly two decimals in plain notation, rounded
 *    HALF_UP ("460.00", "45000.00"), through BigDecimal so neither the
 *    locale's decimal mark nor Double's scientific form ("1.0E7") reaches it;
 *  - sender: [Senders.normalizeSender], "" when there is none;
 *  - time: whole minutes since 1970-01-01T00:00 of the local receivedAt, read
 *    with ZoneOffset.UTC as a plain counter (no zone conversion), floor-divided
 *    by [WINDOW_MINUTES] into a bucket.
 */
object Dedup {

    /** Same sender + same text within this window = one delivery (live
     *  receipt vs inbox row, network re-delivery). A message also answers to
     *  the buckets either side of its own, so two readings a few seconds apart
     *  across a bucket edge still meet: readings under [WINDOW_MINUTES] apart
     *  always do, and readings more than twice it apart never do. */
    const val WINDOW_MINUTES = 2L

    /** [key]: what a new row stores. [aliases]: keys under which the same
     *  message may already be banked. */
    data class Identity(val key: String, val aliases: List<String>) {
        val all: List<String> get() = listOf(key) + aliases
    }

    /**
     * The parser-2026.1.0-kt key (6bd77fb5's Pipeline.dedupKey), frozen:
     * amount|normalizeMerchant(keyMerchant)|keyReference|day.
     *
     * Rows banked before message identity existed are stored under exactly
     * this string, and the inbox scan finds them only by recomputing it. It
     * therefore reads [ParsedSms.keyMerchant] and [ParsedSms.keyReference],
     * never the payee and reference the parser has since learned to read
     * better, and keeps Double.toString for the amount.
     */
    fun legacyKey(parsed: ParsedSms, at: LocalDateTime): String = listOf(
        parsed.amount?.toString().orEmpty(),
        Engine.normalizeMerchant(parsed.keyMerchant),
        parsed.keyReference.orEmpty(),
        at.toLocalDate().toString(),
    ).joinToString("|")

    /**
     * Every key [parsed] (read from [body]) answers to.
     *
     * @param sender     originating address, null for a paste
     * @param receivedAt when the phone received it, null for a paste
     * @param at         parsed.occurredAt ?: receivedAt ?: the paste's chosen
     *   time (what the row stores)
     */
    fun identity(
        parsed: ParsedSms,
        body: String,
        sender: String?,
        receivedAt: LocalDateTime?,
        at: LocalDateTime,
    ): Identity {
        val legacy = legacyKey(parsed, at)

        val reference = parsed.referenceNumber?.takeIf { it.isNotBlank() }
        if (reference != null) {
            return Identity(
                key("ref", parsed.type, parsed.account, reference, amount(parsed.amount)),
                listOf(legacy))
        }

        val text = textHash(body)
        val paste = key("paste", text, at.toLocalDate().toString())
        if (receivedAt == null) return Identity(paste, listOf(legacy))

        // The paste alias is what lets the scan recognise a message the user
        // pasted before the SMS was read: a paste of it filed on the day it is
        // stored under has exactly this key.
        val from = Senders.normalizeSender(sender)
        val minutes = Math.floorDiv(receivedAt.toEpochSecond(ZoneOffset.UTC), 60L)
        val bucket = Math.floorDiv(minutes, WINDOW_MINUTES)
        fun msg(b: Long) = key("msg", from, b.toString(), text)
        return Identity(msg(bucket), listOf(msg(bucket - 1), msg(bucket + 1), paste, legacy))
    }

    private fun key(vararg parts: String) = (listOf("v2") + parts).joinToString("|")

    private fun amount(value: Double?): String =
        value?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).toPlainString() }
            .orEmpty()

    private const val HEX = "0123456789abcdef"

    /** Written out rather than String.format, which takes the device locale. */
    private fun textHash(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(Parsing.normalizeText(body).toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }
}
