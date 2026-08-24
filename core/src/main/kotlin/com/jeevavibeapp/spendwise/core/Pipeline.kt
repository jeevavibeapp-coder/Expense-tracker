package com.jeevavibeapp.spendwise.core

import java.time.LocalDateTime

/**
 * What happens to an incoming SMS, as a pure decision.
 *
 * This sequence is the most consequential code in the app: it decides what
 * lands in someone's ledger and what gets held back. Keeping it here — with
 * lookups injected rather than a database reached into — means the whole
 * flow can be tested on a JVM in milliseconds, including the cases that are
 * awkward to reproduce on a device, like a spoofed header or a blocked
 * sender.
 */
object Pipeline {

    sealed class Outcome {
        /** A real transaction. `status` is what the ledger should record. */
        data class Capture(
            val parsed: ParsedSms,
            val merchantName: String?,
            val categoryId: String?,
            val confidence: Int,
            val status: String,
            val dedupKey: String,
            val needsCategory: Boolean,
        ) : Outcome()

        /** Held for the user to judge. Never silently discarded. */
        data class Hold(val parsed: ParsedSms, val verdict: RiskAssessment,
                        val reason: String) : Outcome()

        /** Not a transaction at all: an OTP, a promo, a personal message. */
        data object NotFinancial : Outcome()
    }

    /** Name, category and confidence for a payee, from whatever evidence exists. */
    data class Resolution(val merchantName: String?, val categoryId: String?,
                          val confidence: Int)

    const val STATUS_CONFIRMED = "confirmed"
    const val STATUS_PENDING = "pending_confirmation"
    const val STATUS_REVIEW = "needs_review"

    /**
     * @param lookupSender what is stored about this sender, or null if new
     * @param resolve      name/category/confidence for a raw payee string
     * @param autoAt       confidence at or above which we save without asking
     * @param confirmAt    confidence at or above which we ask rather than bury
     */
    fun ingest(
        sender: String?,
        body: String,
        now: LocalDateTime,
        lookupSender: (String) -> SenderRegistry?,
        resolve: (rawMerchant: String?, amount: Double?, at: LocalDateTime) -> Resolution,
        autoAt: Int = 80,
        confirmAt: Int = 50,
    ): Outcome {
        // 1. Parse FIRST, and judge the sender second. The parse gate is
        //    entirely generic on purpose: knowing the sender may enrich a
        //    message that already looks like a transaction, but it must never
        //    be able to MAKE something a transaction, or a spoofed header
        //    could inject rows into the ledger.
        val parsed = Parsing.parse(body)
        if (parsed.amount == null || !parsed.looksTransactional) return Outcome.NotFinancial

        // 2. Now the sender, with whatever the app has learned about it.
        val registry = sender?.let { lookupSender(Senders.normalizeSender(it)) }
        val verdict = Senders.assess(sender, body, registry)
        if (verdict.action == Senders.ACTION_QUARANTINE) {
            return Outcome.Hold(parsed, verdict, Senders.explain(verdict))
        }

        // A message shaped like a debit but worded like a scam or a promo is
        // never banked. Where it goes depends on which: scam wording means
        // someone is targeting this person and they should see it, while a
        // pre-debit notice or an offer is just noise.
        if (!parsed.matched) {
            return if (verdict.risk > 0) Outcome.Hold(parsed, verdict, Senders.explain(verdict))
                   else Outcome.NotFinancial
        }

        // 3. Resolve the payee, then let the sender's risk pull confidence
        //    DOWN — never up. An unverified sender should make the app ask
        //    more often, not less.
        val at = parsed.occurredAt ?: now
        val resolution = resolve(parsed.rawMerchant, parsed.amount, at)
        val confidence = (resolution.confidence + verdict.confidenceDelta).coerceIn(0, 100)

        val status = when (Engine.decide(confidence, autoAt, confirmAt)) {
            Engine.DECISION_AUTO -> STATUS_CONFIRMED
            Engine.DECISION_CONFIRM -> STATUS_PENDING
            else -> STATUS_REVIEW
        }

        return Outcome.Capture(
            parsed = parsed,
            merchantName = resolution.merchantName,
            categoryId = resolution.categoryId,
            confidence = confidence,
            status = status,
            dedupKey = dedupKey(parsed, at),
            needsCategory = resolution.categoryId == null,
        )
    }

    /**
     * Identity of a payment, independent of how it was worded.
     *
     * Several banks send both an SMS and a UPI confirmation for one payment,
     * and the app is launched repeatedly, rescanning the same inbox. Amount +
     * folded merchant + reference + DAY is stable across all of that, while
     * still separating two genuinely different coffees on the same morning
     * once a reference number is present.
     */
    fun dedupKey(parsed: ParsedSms, at: LocalDateTime): String = listOf(
        parsed.amount?.toString().orEmpty(),
        Engine.normalizeMerchant(parsed.rawMerchant),
        parsed.referenceNumber.orEmpty(),
        at.toLocalDate().toString(),
    ).joinToString("|")
}
