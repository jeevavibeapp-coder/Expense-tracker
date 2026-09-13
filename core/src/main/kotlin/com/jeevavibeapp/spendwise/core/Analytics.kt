package com.jeevavibeapp.spendwise.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Aggregation over a ledger: recurring charges, self-transfers, refunds.
 * Pure Kotlin over a list of transactions — the caller does the querying.
 *
 * Transfers and refunds exist because without them the HEADLINE NUMBERS are
 * simply wrong: moving money between your own accounts is counted as both
 * spending and income, and a refund inflates income instead of reducing the
 * original spend.
 */
object Analytics {

    const val RECURRING_WINDOW_DAYS = 400L
    const val TRANSFER_WINDOW_MIN = 90L      // a self-transfer settles within ~1.5h
    const val REFUND_WINDOW_DAYS = 45L       // merchant refunds land within ~45 days
    private const val AMOUNT_EPS = 0.005     // half a paisa

    /** Repeating charges, with a predicted next due date.
     *
     * A merchant qualifies with at least 3 charges (4 for weekly) at a
     * weekly/monthly/quarterly cadence AND a stable amount. Two coincidental
     * purchases at the same shop are not a subscription, and calling them one
     * teaches the user to distrust the whole screen. */
    fun detectRecurring(txs: List<Tx>, today: LocalDate, limit: Int = 6): List<Recurring> {
        val cutoff = today.minusDays(RECURRING_WINDOW_DAYS)
        val byMerchant = txs
            .filter { it.type == "expense" && !it.merchantName.isNullOrBlank() &&
                      !it.occurredAt.toLocalDate().isBefore(cutoff) }
            .groupBy { it.merchantName!! }

        val out = mutableListOf<Recurring>()
        for ((name, items) in byMerchant) {
            val sorted = items.sortedBy { it.occurredAt }
            if (sorted.size < 2) continue
            val dates = sorted.map { it.occurredAt.toLocalDate() }
            val gaps = (0 until dates.size - 1)
                .map { ChronoUnit.DAYS.between(dates[it], dates[it + 1]) }
                .filter { it > 0 }
            if (gaps.isEmpty()) continue
            val med = gaps.sorted()[gaps.size / 2]
            val cadence = when (med) {
                in 6..8 -> "weekly"
                in 25..35 -> "monthly"
                in 85..95 -> "quarterly"
                else -> continue
            }
            if (sorted.size < (if (cadence == "weekly") 4 else 3)) continue
            val recent = sorted.takeLast(4).map { it.amount }
            val mean = recent.average()
            if (mean <= 0 || (recent.max() - recent.min()) > 0.25 * mean) continue
            val nextDue = dates.last().plusDays(med)
            val daysLeft = ChronoUnit.DAYS.between(today, nextDue)
            // A full period overdue means it was probably cancelled.
            if (daysLeft < -med) continue
            out += Recurring(name, round2(recent.last()), cadence, nextDue, daysLeft)
        }
        return out.sortedBy { it.daysLeft }.take(limit)
    }

    /** Debit and credit of the same amount within minutes: money that never
     *  left. Deliberately conservative — a false pair HIDES real spending, so
     *  it takes equal amounts, opposite directions and a short window. */
    fun detectTransfers(txs: List<Tx>): List<TransferPair> {
        // Bucket credits by exact paise so each debit probes only what it
        // could possibly pair with. The naive nested loop is O(debits x
        // credits) — about 3.5M comparisons at 12k rows. Matching requires
        // equal amounts anyway, so this index is exact, not an approximation.
        val creditsByPaise = txs.filter { it.type == "income" }
            .groupBy { paise(it.amount) }
        val used = mutableSetOf<String>()
        val pairs = mutableListOf<TransferPair>()
        for (d in txs.sortedBy { it.occurredAt }) {
            if (d.type != "expense") continue
            val bucket = creditsByPaise[paise(d.amount)] ?: continue
            for (c in bucket) {
                if (c.id in used) continue
                val minutes = abs(ChronoUnit.MINUTES.between(d.occurredAt, c.occurredAt))
                if (minutes <= TRANSFER_WINDOW_MIN) {
                    used += c.id
                    pairs += TransferPair(d.id, c.id, round2(d.amount), d.occurredAt)
                    break
                }
            }
        }
        return pairs
    }

    /** A credit from a merchant previously paid is a refund, matched to the
     *  most recent debit at least as large. Reported, never auto-applied: the
     *  user's ledger is not silently rewritten. */
    fun detectRefunds(txs: List<Tx>): List<RefundPair> {
        val byMerchant = txs.filter { !it.merchantName.isNullOrBlank() }
            .groupBy { it.merchantName!! }
        val out = mutableListOf<RefundPair>()
        for ((name, items) in byMerchant) {
            val sorted = items.sortedBy { it.occurredAt }
            val debits = sorted.filter { it.type == "expense" }
            if (debits.isEmpty()) continue
            for (credit in sorted.filter { it.type == "income" }) {
                // debits is ascending, so walking it backwards finds the most
                // recent qualifying one first — no best-so-far scan needed.
                for (d in debits.asReversed()) {
                    if (d.occurredAt > credit.occurredAt) continue
                    if (ChronoUnit.DAYS.between(d.occurredAt, credit.occurredAt)
                            > REFUND_WINDOW_DAYS) break
                    if (d.amount + AMOUNT_EPS < credit.amount) continue
                    out += RefundPair(credit.id, d.id, name, round2(credit.amount),
                                      credit.occurredAt)
                    break
                }
            }
        }
        return out
    }

    /** All-time totals with self-transfers and refunds removed. Without this
     *  "Total spent" and "Money health" are just wrong. */
    fun moneyFlow(txs: List<Tx>): MoneyFlow {
        val transferIds = detectTransfers(txs)
            .flatMap { listOf(it.debitId, it.creditId) }.toSet()
        val refunds = detectRefunds(txs)
        val refundCreditIds = refunds.map { it.creditId }.toSet()

        var income = 0.0
        var expense = 0.0
        for (t in txs) {
            if (t.id in transferIds) continue
            when (t.type) {
                "income" -> if (t.id !in refundCreditIds) income += t.amount
                "expense" -> expense += t.amount
            }
        }
        // A refund reduces the original spending rather than counting as income.
        expense -= refunds.sumOf { it.amount }
        return MoneyFlow(round2(income), round2(maxOf(0.0, expense)),
                         transferIds.size / 2, refunds.size)
    }

    private fun paise(v: Double): Long = (v * 100).roundToLong()
    internal fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}

/** The minimum a transaction needs to be for aggregation. */
data class Tx(
    val id: String,
    val amount: Double,
    val type: String,                 // "expense" | "income"
    val occurredAt: LocalDateTime,
    val merchantName: String? = null,
    val categoryId: String? = null,
)

data class Recurring(
    val name: String,
    val amount: Double,
    val cadence: String,
    val nextDue: LocalDate,
    val daysLeft: Long,
)

data class TransferPair(val debitId: String, val creditId: String,
                        val amount: Double, val at: LocalDateTime)

data class RefundPair(val creditId: String, val debitId: String, val merchant: String,
                      val amount: Double, val at: LocalDateTime)

data class MoneyFlow(val income: Double, val expense: Double,
                     val transfers: Int, val refunds: Int)
