package com.jeevavibeapp.spendwise.core

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A pasted bank message: read and shown first, written only when the user
 * says so.
 *
 * Pasting is the only way bank data reaches the no-SMS build — the one that
 * installs where Play Protect refuses SMS permissions — and it used to run
 * straight through [Pipeline.ingest] into the ledger. A misread amount, date,
 * direction or payee became a row nobody had looked at.
 *
 * Hence two steps. [preview] reads the message with the same decision a
 * captured SMS gets and returns the row it WOULD store, or why there is none.
 * [commit] turns that row, plus whatever the user corrected on screen, into
 * exactly what is written. Neither writes anything: the lookups they are given
 * are reads, and the caller stores what [commit] returns field for field — so
 * what was on screen when Save was tapped is what lands.
 */
object Paste {

    /**
     * The row Save writes if nothing on the card is changed. Everything the
     * card shows is here, so a card can be drawn from this alone.
     */
    data class Draft(
        val amount: Double,
        val type: String,
        /** The payee as the bank wrote it. Not editable: it is what the next
         *  message from the same payee will carry, so it is the key the engine
         *  learns under and part of the dedup key. */
        val rawMerchant: String?,
        val merchantName: String?,
        val categoryId: String?,
        val referenceNumber: String?,
        val occurredAt: LocalDateTime,
        val confidence: Int,
        val status: String,
        /** Unhashed. The caller hashes it exactly as it hashes a capture's. */
        val dedupKey: String,
        /** What the parser read. Kept because [commit] derives the dedup key
         *  from the message rather than from the corrections — see there. */
        val parsed: ParsedSms,
    ) {
        /** False when the message carried no date, so [occurredAt] is the
         *  moment it was pasted — a guess the screen should own up to. */
        val dated: Boolean get() = parsed.occurredAt != null
    }

    /** A row the ledger already holds under the same dedup key. A deleted
     *  row counts: the key is unique, so it refuses a second row either way,
     *  and saying "already there" about a row the user cannot see would be a
     *  dead end without [deleted] to explain it. */
    data class Banked(
        val id: String,
        val amount: Double,
        val type: String,
        val merchant: String?,
        val occurredAt: LocalDateTime,
        val deleted: Boolean,
    )

    sealed class Preview {
        data class Transaction(
            val draft: Draft,
            /**
             * Why the message is suspect — the wording a captured SMS is held
             * back for. The user is looking at it right now, so it is shown
             * rather than quarantined, and saving it is them vouching for it
             * the way approving a held message is: the draft is filed
             * confirmed. Null for an ordinary alert.
             */
            val warning: String? = null,
            /** Already in the ledger. Saving would write nothing, because the
             *  dedup key is unique. */
            val duplicate: Banked? = null,
        ) : Preview()

        /** Nothing to save, and why — so the user knows whether to paste
         *  more of the message or nothing at all. */
        data class NotATransaction(val reason: String) : Preview()
    }

    const val NOTHING_PASTED =
        "There's nothing to read yet. Paste the whole message from your bank."
    const val NO_AMOUNT =
        "There's no amount in it that SpendWise can use. A bank message always " +
        "names one, like Rs.450."
    const val NO_MOVEMENT =
        "It doesn't say money moved in or out of an account. A bank alert says " +
        "debited or credited and names an account, card or UPI ID — if you copied " +
        "only part of the message, paste all of it."
    const val NOT_MOVED_YET =
        "It reads like an offer, a request, a reminder or a payment that didn't go " +
        "through — not money that has already moved."

    /**
     * What Save would store for [body], without storing anything.
     *
     * @param resolve      name, category and confidence for a raw payee — a
     *   read of what the engine has learned, as the capture path uses
     * @param banked       the ledger row already filed under a dedup key
     * @param categoryType the side of the ledger ("expense" or "income") of a
     *   category the card can offer, or null for one it cannot show
     */
    fun preview(
        body: String,
        now: LocalDateTime,
        resolve: (rawMerchant: String?, amount: Double?, at: LocalDateTime) -> Pipeline.Resolution,
        banked: (dedupKey: String) -> Banked?,
        categoryType: (categoryId: String) -> String?,
        autoAt: Int = 80,
        confirmAt: Int = 50,
    ): Preview {
        if (body.isBlank()) return Preview.NotATransaction(NOTHING_PASTED)

        // The capture path's own decision, given no sender: a paste has none,
        // and Senders reads a missing sender as no signal either way, so the
        // message is judged on its words alone.
        val outcome = Pipeline.ingest(
            sender = null, body = body, now = now,
            lookupSender = { null }, resolve = resolve,
            autoAt = autoAt, confirmAt = confirmAt,
        )

        return when (outcome) {
            Pipeline.Outcome.NotFinancial -> Preview.NotATransaction(whyNot(Parsing.parse(body)))

            is Pipeline.Outcome.Capture -> {
                val parsed = outcome.parsed
                val amount = parsed.amount ?: return Preview.NotATransaction(whyNot(parsed))
                val draft = Draft(
                    amount = amount,
                    type = parsed.type,
                    rawMerchant = parsed.rawMerchant,
                    merchantName = outcome.merchantName,
                    categoryId = sameSide(outcome.categoryId, parsed.type, categoryType),
                    referenceNumber = parsed.referenceNumber,
                    occurredAt = parsed.occurredAt ?: now,
                    confidence = outcome.confidence,
                    status = outcome.status,
                    dedupKey = outcome.dedupKey,
                    parsed = parsed,
                )
                Preview.Transaction(draft, duplicate = banked(draft.dedupKey))
            }

            is Pipeline.Outcome.Hold -> {
                val parsed = outcome.parsed
                val amount = parsed.amount ?: return Preview.NotATransaction(whyNot(parsed))
                val at = parsed.occurredAt ?: now
                // Pipeline stops before naming the payee of a message it
                // holds. The card still needs a suggestion to correct, and
                // asking for one is a read like every other lookup here.
                val r = resolve(parsed.rawMerchant, amount, at)
                val draft = Draft(
                    amount = amount,
                    type = parsed.type,
                    rawMerchant = parsed.rawMerchant,
                    merchantName = r.merchantName,
                    categoryId = sameSide(r.categoryId, parsed.type, categoryType),
                    referenceNumber = parsed.referenceNumber,
                    occurredAt = at,
                    // The payee's own score, not one the sender verdict
                    // flattened: that verdict is the warning beside it, and
                    // the user overruling it is what makes this confirmed.
                    confidence = r.confidence,
                    status = Pipeline.STATUS_CONFIRMED,
                    dedupKey = Pipeline.dedupKey(parsed, at),
                    parsed = parsed,
                )
                Preview.Transaction(draft, caution(parsed, outcome.verdict), banked(draft.dedupKey))
            }
        }
    }

    /**
     * The card's fields as the user left them. [fields] starts each one on
     * the draft's own value, so an untouched card commits the draft unchanged.
     */
    data class Fields(
        val amount: Double,
        val type: String,
        /** As typed. It starts on the engine's name for the payee, or on the
         *  bank's own string when the engine could not name it. */
        val merchant: String,
        val categoryId: String?,
        val date: LocalDate,
    )

    fun fields(draft: Draft): Fields = Fields(
        amount = draft.amount,
        type = draft.type,
        merchant = draft.merchantName ?: draft.rawMerchant ?: "",
        categoryId = draft.categoryId,
        date = draft.occurredAt.toLocalDate(),
    )

    /** What the engine is taught when the user names the payee on the card:
     *  the same lesson confirming the row afterwards would have taught. */
    data class Learning(
        val rawMerchant: String,
        val merchantName: String,
        val categoryId: String?,
        val isCorrection: Boolean,
    )

    /** Exactly the row Save writes, and what the engine learns from it. */
    data class Commit(
        val amount: Double,
        val type: String,
        val rawMerchant: String?,
        val merchantName: String?,
        val categoryId: String?,
        val referenceNumber: String?,
        val occurredAt: LocalDateTime,
        val confidence: Int,
        val status: String,
        val dedupKey: String,
        val learn: Learning?,
    )

    /**
     * The row Save writes from [draft] as the card now shows it.
     *
     * An untouched card is the draft, field for field — including a status
     * below confirmed, because a payee the engine has never seen is saved for
     * review and learned once the user confirms it, as the Python build's
     * import says. Naming the payee on the card IS that confirmation, so it
     * is filed confirmed and the engine learns it now.
     *
     * @return null for an amount or direction the ledger cannot hold. The
     *   card refuses those before Save; this is the last check, not the first.
     */
    fun commit(draft: Draft, fields: Fields = fields(draft)): Commit? {
        val amount = Parsing.safeAmount(fields.amount) ?: return null
        if (fields.type != "expense" && fields.type != "income") return null

        val name = fields.merchant.trim()
        val renamed = name != fields(draft).merchant.trim()
        // Picking a category names the payee as surely as typing its name.
        // Clearing one does not: flipping a row to income clears a spending
        // category on the way, and that is not the user vouching for anything.
        val categorised = fields.categoryId != null && fields.categoryId != draft.categoryId
        val confirmation = if (name.isNotEmpty() && (renamed || categorised))
            Pipeline.confirm(draft.merchantName, draft.categoryId, name, fields.categoryId)
        else null

        // Only the date is editable. The time of day is the message's own, or
        // the paste's for an undated one, and the hour histogram behind
        // merchant scoring is built from it.
        val at = fields.date.atTime(draft.occurredAt.toLocalTime())

        return Commit(
            amount = amount,
            type = fields.type,
            rawMerchant = draft.rawMerchant,
            // Emptied by the user: the row falls back to the bank's string,
            // which is what an unnamed capture shows anyway.
            merchantName = confirmation?.merchantName ?: if (renamed) null else draft.merchantName,
            // As chosen, Uncategorised included. Pipeline.confirm keeps the
            // stored category when none is named, which is right for a row
            // confirmed later and wrong for a card where Uncategorised is a
            // chip the user tapped.
            categoryId = fields.categoryId,
            referenceNumber = draft.referenceNumber,
            occurredAt = at,
            confidence = confirmation?.confidence ?: draft.confidence,
            status = confirmation?.status ?: draft.status,
            // The message's identity, not the corrections'. The inbox scan and
            // a second paste both read the message itself, so a key built from
            // a corrected amount would let the misread copy in beside this
            // one. Only an undated message takes the chosen day: there the day
            // is not in the message, and the capture path uses the day it
            // arrived — which is the day the user is choosing.
            dedupKey = Pipeline.dedupKey(draft.parsed, draft.parsed.occurredAt ?: at),
            learn = confirmation?.let { c ->
                Learning(
                    // A message with no payee in it has nothing an incoming
                    // SMS could match, so the confirmed name is the key — the
                    // fallback confirming a row later uses too.
                    rawMerchant = draft.rawMerchant ?: c.merchantName,
                    merchantName = c.merchantName,
                    categoryId = fields.categoryId,
                    // Replacing the engine's category is a correction as much
                    // as renaming its payee is, and scoring weighs the two
                    // the same way.
                    isCorrection = c.isCorrection || (categorised && draft.categoryId != null),
                )
            },
        )
    }

    /**
     * A suggested category, only if the card can show it on this row.
     *
     * The engine names a category by payee alone, so a refund from a shop
     * arrives suggesting the shop's spending category. The picker offers one
     * side of the ledger at a time and could not show that chip — and a
     * choice the user cannot see on the card is not one they made.
     */
    private fun sameSide(categoryId: String?, type: String,
                         categoryType: (String) -> String?): String? =
        categoryId?.takeIf { categoryType(it) == type }

    private fun whyNot(parsed: ParsedSms): String = when {
        parsed.amount == null -> NO_AMOUNT
        !parsed.looksTransactional -> NO_MOVEMENT
        else -> NOT_MOVED_YET
    }

    /** The indicators [Senders.explain] has a sentence for. Its other
     *  sentences are about who SENT the message, and a paste has no sender. */
    private val EXPLAINED = setOf(
        "credential_request", "apk_download", "url_shortener",
        "account_block_threat", "prize_or_refund_lure",
    )

    private fun caution(parsed: ParsedSms, verdict: RiskAssessment): String {
        val found = verdict.indicators
        return when {
            found.any { it in EXPLAINED } -> Senders.explain(verdict)
            "callback_mobile_number" in found ->
                "It asks you to call a personal mobile number. Banks give toll-free numbers."
            "payment_request" in found -> "It asks you to send money. A bank alert never does."
            "suspicious_domain" in found -> "It links to a website address banks don't use."
            !parsed.matched -> NOT_MOVED_YET
            else -> "It has the marks of a scam message. Check it against your bank's app " +
                    "before saving it."
        }
    }
}
