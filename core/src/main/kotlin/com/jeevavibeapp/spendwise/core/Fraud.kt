package com.jeevavibeapp.spendwise.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Fraud detection over the user's own history: the five questions a ledger
 * can answer about a charge the moment it lands.
 *
 * Distinct from [Insights.anomalies], which is a monthly report section about
 * large charges at a merchant the user already knows. That cannot see a card
 * charged twice in four minutes, cannot see a payee that has never appeared
 * before, and has nowhere to record that the user looked and it was fine.
 * This can, and every signal it raises is about ONE transaction at the moment
 * it is filed.
 *
 * Pure, like the rest of :core — the caller supplies the ledger and stores
 * the result. Every threshold here is a constant rather than a literal buried
 * in a condition, because they are the whole behaviour and a test that cannot
 * name them cannot pin them.
 */
object Fraud {

    const val DUPLICATE_WINDOW_MIN = 10L
    const val OUTLIER_SIGMA = 3.0
    /** An excess smaller than this is noise whatever the sigma says. The same
     *  reasoning as Insights.ANOMALY_FLOOR: on a ledger of Rs.50 chai the
     *  arithmetic will happily call Rs.62 a four-sigma event. */
    const val OUTLIER_FLOOR = 300.0
    const val ABNORMAL_DAY_FACTOR = 3.0
    const val NEW_MERCHANT_MULTIPLE = 2.0
    /** Charges needed before "usual" means anything. Under this every
     *  detector that compares against the user's own distribution stays
     *  silent rather than guessing from four numbers. */
    const val MIN_HISTORY = 8

    /**
     * How recent a charge has to be for an alert about it to be worth
     * raising at all — see [worthAlerting].
     *
     * An alert exists to be acted on, and those windows close: a bank wants
     * to hear about a double charge this week, not one from the spring. It is
     * also what keeps a first launch usable. The inbox scan backfills ninety
     * days of messages at once, so without this the first thing a new user
     * sees is a tab reading "Alerts (23)" over purchases they have already
     * lived through — which teaches them to ignore it before it has ever
     * carried anything live.
     */
    const val ALERT_WINDOW_DAYS = 30L

    const val DUPLICATE = "duplicate"
    const val OUTLIER = "high_value_outlier"
    const val OVER_LIMIT = "high_value_limit"
    const val ABNORMAL_DAY = "abnormal_spend"
    const val NEW_MERCHANT = "unusual_merchant"

    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"

    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /** Half a paisa: the same tolerance Analytics uses to call two amounts
     *  equal, because a double that came back through SQLite is not reliably
     *  bit-identical to the one that went in. */
    private const val AMOUNT_EPS = 0.005

    /**
     * Every signal [tx] raises against [ledger].
     *
     * [ledger] is the rest of the user's transactions — soft-deleted rows
     * excluded by the caller, [tx] itself tolerated and filtered out here so
     * a caller that passes the whole ledger after inserting gets the same
     * answer as one that passes it before.
     *
     * [highValueLimit] is the amount the user typed on the settings screen.
     * Null or zero means they set none, which is not the same as zero.
     */
    fun evaluate(tx: Tx, ledger: List<Tx>, highValueLimit: Double? = null): List<FraudAlert> {
        if (tx.type != "expense") return emptyList()

        val others = ledger.filter { it.id != tx.id }
        val amount = tx.amount
        val name = tx.merchantName?.takeIf { it.isNotBlank() }
        val day = tx.occurredAt.toLocalDate()
        val out = mutableListOf<FraudAlert>()

        // 1. The same amount at the same payee, minutes apart. The Python
        //    original compares amounts only, with no type filter, so a salary
        //    credit landing within ten minutes of a purchase of the same size
        //    reported the purchase as a duplicate charge — and a self
        //    transfer, which is a debit and a credit of exactly equal amounts
        //    close together, was guaranteed to trip it. Expenses only here.
        val duplicate = others.any {
            it.type == "expense" &&
                sameAmount(it.amount, amount) &&
                abs(ChronoUnit.MINUTES.between(it.occurredAt, tx.occurredAt)) <=
                DUPLICATE_WINDOW_MIN &&
                (name == null || it.merchantName == name)
        }
        if (duplicate) {
            out += FraudAlert(
                key = "$DUPLICATE:${tx.id}", txId = tx.id, kind = DUPLICATE, severity = MEDIUM,
                message = "Possible duplicate: ${Insights.money(amount)}" +
                    (if (name != null) " at $name" else "") +
                    ", twice within $DUPLICATE_WINDOW_MIN minutes.")
        }

        val history = others.filter { it.type == "expense" }.map { it.amount }
        val enoughHistory = history.size >= MIN_HISTORY
        val mean = if (history.isEmpty()) 0.0 else history.average()

        // 2. Far outside the user's own distribution.
        if (enoughHistory) {
            val spread = stdev(history, mean)
            // The Python original substitutes 1.0 for a zero standard
            // deviation, which turns a ledger of identical amounts into a
            // machine for manufacturing high-severity alerts: eight charges
            // of Rs.50 make any Rs.54 charge a four-sigma event. No spread
            // means there is no distribution to be an outlier of, so the
            // detector stays quiet instead.
            if (spread > 0 && amount - mean >= OUTLIER_FLOOR) {
                val sigma = (amount - mean) / spread
                if (sigma >= OUTLIER_SIGMA) {
                    out += FraudAlert(
                        key = "$OUTLIER:${tx.id}", txId = tx.id, kind = OUTLIER, severity = HIGH,
                        message = "${Insights.money(amount)} is " +
                            "${Math.round(sigma * 10) / 10.0} standard deviations above " +
                            "your average charge of ${Insights.money(mean)}.")
                }
            }
        }

        // 3. The line the user drew themselves. Not gated on history: they
        //    stated the number, and an app that ignores it until it has eight
        //    other charges to compare against has simply not honoured it.
        if (highValueLimit != null && highValueLimit > 0 && amount + AMOUNT_EPS >= highValueLimit) {
            out += FraudAlert(
                key = "$OVER_LIMIT:${tx.id}", txId = tx.id, kind = OVER_LIMIT, severity = HIGH,
                message = "${Insights.money(amount)} is at or above the " +
                    "${Insights.money(highValueLimit)} alert amount you set.")
        }

        // 4. A day far heavier than the user's own average day.
        if (enoughHistory) {
            val dayTotal = amount + others
                .filter { it.type == "expense" && it.occurredAt.toLocalDate() == day }
                .sumOf { it.amount }
            val dailyAverage = others
                .filter { it.type == "expense" && it.occurredAt.toLocalDate() < day }
                .groupBy { it.occurredAt.toLocalDate() }
                .map { (_, rows) -> rows.sumOf { it.amount } }
                .let { if (it.isEmpty()) 0.0 else it.average() }
            if (dailyAverage > 0 && dayTotal >= dailyAverage * ABNORMAL_DAY_FACTOR) {
                // Keyed by the DAY, not by this transaction. The claim is
                // about the day, and the Python original re-raises it for
                // every further charge that day — so one heavy Saturday
                // arrives as six identical alerts saying the same sentence.
                out += FraudAlert(
                    key = "$ABNORMAL_DAY:$day", txId = tx.id, kind = ABNORMAL_DAY,
                    severity = MEDIUM,
                    message = "${Insights.money(dayTotal)} spent on ${day.format(DAY)}, " +
                        "against a daily average of ${Insights.money(dailyAverage)}.")
            }
        }

        // 5. A payee that has never appeared before, for a large amount.
        if (name != null && enoughHistory && amount > mean * NEW_MERCHANT_MULTIPLE) {
            if (others.none { it.merchantName == name }) {
                out += FraudAlert(
                    key = "$NEW_MERCHANT:${tx.id}", txId = tx.id, kind = NEW_MERCHANT,
                    severity = LOW,
                    message = "First charge at $name, and ${Insights.money(amount)} is well " +
                        "above your average charge of ${Insights.money(mean)}.")
            }
        }

        return out.sortedByDescending { rank(it.severity) }
    }

    /**
     * Whether a charge is recent enough to raise alerts at all.
     *
     * Separate from [evaluate] because it answers a different question: that
     * one asks whether a charge looks wrong, this asks whether saying so is
     * still of any use. Callers that file transactions ask this first.
     *
     * No upper bound. A charge dated ahead of the clock — a bank's timestamp,
     * a phone in the wrong timezone — is the one thing that is certainly not
     * stale.
     */
    fun worthAlerting(occurredAt: LocalDateTime, now: LocalDateTime): Boolean =
        !occurredAt.toLocalDate().isBefore(now.toLocalDate().minusDays(ALERT_WINDOW_DAYS))

    /** High first. Exposed because the screen sorts a mixed list of stored
     *  alerts and cannot re-derive this from the severity string. */
    fun rank(severity: String): Int = when (severity) {
        HIGH -> 3
        MEDIUM -> 2
        else -> 1
    }

    private fun sameAmount(a: Double, b: Double): Boolean =
        (a * 100).roundToLong() == (b * 100).roundToLong()

    private fun stdev(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        return sqrt(values.sumOf { val d = it - mean; d * d } / values.size)
    }
}

/**
 * One raised signal.
 *
 * [key] is what makes raising it twice impossible: it is derived from what
 * the claim is ABOUT — this transaction, or this day — so a re-ingest, a
 * rescan or a second charge on an already-flagged day cannot produce a second
 * copy. Storage enforces it with a unique index; nothing has to remember to
 * check.
 *
 * No JSON details blob. The Python original stores one and no screen has ever
 * read it: the message already carries every number that produced the claim.
 */
data class FraudAlert(
    val key: String,
    val txId: String,
    val kind: String,
    val severity: String,
    val message: String,
)
