package com.jeevavibeapp.spendwise.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Local spending intelligence: forecast, cash flow, trends, merchants,
 * anomalies, savings. Pure Kotlin over the ledger.
 *
 * Three rules shape every function here, learned from a first pass that
 * produced confident nonsense:
 *
 *  - Say the number that produced the claim. "You spent more on food" is a
 *    horoscope; "Food is 34% above your own 3-month average of Rs.8,200" can
 *    be checked and argued with.
 *  - Never speak from too little data. Every function has a minimum-evidence
 *    gate and returns nothing rather than guessing.
 *  - Never invent a recommendation the numbers do not support.
 */
object Insights {

    const val ANOMALY_MULTIPLE = 2.5     // times the merchant's own median
    const val ANOMALY_FLOOR = 300.0      // below this a multiple is noise
    const val ANOMALY_MIN_HISTORY = 4    // prior charges before "usual" means anything
    const val SMALL_TICKET = 200.0
    const val SMALL_TICKET_MIN_COUNT = 8

    /** Where this month lands: the run-rate so far, plus recurring charges
     *  already known to be due before it ends.
     *
     * Only ever for the month in progress — projecting a finished month is
     * just its total, dressed up. Needs four elapsed days: a run-rate off one
     * or two days swings by hundreds of percent and is actively misleading. */
    fun forecast(txs: List<Tx>, month: YearMonth, today: LocalDate,
                 recurring: List<Recurring> = emptyList()): Forecast? {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        if (today < start || !today.isBefore(end)) return null

        val elapsed = ChronoUnit.DAYS.between(start, today).toInt() + 1
        val totalDays = month.lengthOfMonth()
        if (elapsed < 4 || elapsed >= totalDays) return null

        val spent = txs.filter {
            it.type == "expense" && it.occurredAt.toLocalDate() in start..<end
        }.sumOf { it.amount }
        if (spent <= 0) return null

        val remainingDays = totalDays - elapsed
        val runRate = spent / elapsed

        // Recurring charges due later this month are KNOWN money, not a
        // guess, so they are added on top of the run-rate rather than assumed
        // to be inside it. Without this a rent debit on the 28th makes every
        // forecast before the 28th far too low, every single month.
        val upcoming = recurring.filter { it.nextDue > today && it.nextDue < end }
        val committed = upcoming.sumOf { it.amount }

        // Rounded to the nearest hundred on purpose. A projection is not a
        // measurement, and "Rs.62,811.47 projected" claims a precision the
        // arithmetic does not have.
        val projected = ((spent + runRate * remainingDays + committed) / 100.0)
            .roundToLong() * 100.0

        val prevStart = month.minusMonths(1).atDay(1)
        val prevTotal = txs.filter {
            it.type == "expense" && it.occurredAt.toLocalDate() in prevStart..<start
        }.sumOf { it.amount }

        return Forecast(
            spent = Analytics.round2(spent),
            projected = projected,
            runRate = Analytics.round2(runRate),
            committed = Analytics.round2(committed),
            upcoming = upcoming.take(4),
            elapsedDays = elapsed,
            remainingDays = remainingDays,
            prevTotal = if (prevTotal > 0) Analytics.round2(prevTotal) else null,
            vsPrev = if (prevTotal > 0) Analytics.round2(projected - prevTotal) else null,
        )
    }

    /** Income, expense, net and a RUNNING net for the last N months.
     *
     * The running total is the part per-month bars cannot show: three months
     * of small overspend look harmless side by side and are obvious the
     * moment they are accumulated. */
    fun cashFlow(txs: List<Tx>, today: LocalDate, months: Int = 6): List<CashFlowMonth> {
        val first = YearMonth.from(today).minusMonths((months - 1).toLong())
        val out = mutableListOf<CashFlowMonth>()
        var running = 0.0
        for (i in 0 until months) {
            val m = first.plusMonths(i.toLong())
            val a = m.atDay(1)
            val b = m.plusMonths(1).atDay(1)
            val inMonth = txs.filter { it.occurredAt.toLocalDate() in a..<b }
            val income = inMonth.filter { it.type == "income" }.sumOf { it.amount }
            val expense = inMonth.filter { it.type == "expense" }.sumOf { it.amount }
            val net = income - expense
            running += net
            out += CashFlowMonth(m, Analytics.round2(income), Analytics.round2(expense),
                Analytics.round2(net), Analytics.round2(running),
                empty = income == 0.0 && expense == 0.0)
        }
        return out
    }

    /** Per-category spend across N months with a direction.
     *
     * Direction compares the recent half against the older half rather than
     * last month against the one before: one heavy grocery run is not a
     * trend, and calling it one teaches the user to distrust every other
     * number on the screen. */
    fun categoryTrends(txs: List<Tx>, today: LocalDate, months: Int = 6,
                       limit: Int = 6,
                       categoryName: (String?) -> String = { it ?: "Uncategorised" }
    ): List<CategoryTrend> {
        val first = YearMonth.from(today).minusMonths((months - 1).toLong())
        val keys = (0 until months).map { first.plusMonths(it.toLong()) }
        val expenses = txs.filter { it.type == "expense" &&
            !it.occurredAt.toLocalDate().isBefore(first.atDay(1)) }

        val byCat = expenses.groupBy { categoryName(it.categoryId) }
        val half = months / 2
        val out = mutableListOf<CategoryTrend>()
        for ((name, items) in byCat) {
            val series = keys.map { m ->
                Analytics.round2(items.filter { YearMonth.from(it.occurredAt) == m }
                    .sumOf { it.amount })
            }
            val total = series.sum()
            if (total <= 0) continue
            val active = series.count { it > 0 }
            val avgOld = series.take(half).average()
            val avgNew = series.drop(half).average()
            var direction = "steady"
            var change = 0.0
            // A direction needs spend in at least three of the months.
            if (active >= 3 && avgOld > 0) {
                change = (avgNew - avgOld) / avgOld * 100
                direction = when {
                    change >= 15 -> "rising"
                    change <= -15 -> "falling"
                    else -> "steady"
                }
            }
            out += CategoryTrend(name, series, keys, Analytics.round2(total),
                Analytics.round2(total / months), direction, change.roundToInt(), active)
        }
        return out.sortedByDescending { it.total }.take(limit)
    }

    /** Top merchants this month, each compared against ITS OWN prior three
     *  months. Comparing a merchant to itself is the only comparison that
     *  means anything: Rs.9,000 is alarming at a coffee shop and unremarkable
     *  at a landlord. */
    fun merchantInsights(txs: List<Tx>, month: YearMonth, limit: Int = 5): List<MerchantInsight> {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        val base = month.minusMonths(3).atDay(1)

        val current = txs.filter {
            it.type == "expense" && !it.merchantName.isNullOrBlank() &&
            it.occurredAt.toLocalDate() in start..<end
        }.groupBy { it.merchantName!! }

        val prior = txs.filter {
            it.type == "expense" && !it.merchantName.isNullOrBlank() &&
            it.occurredAt.toLocalDate() in base..<start
        }.groupBy { it.merchantName!! }

        return current.entries
            .map { (name, items) -> name to items }
            .sortedByDescending { it.second.sumOf { t -> t.amount } }
            .take(limit)
            .map { (name, items) ->
                val total = Analytics.round2(items.sumOf { it.amount })
                val visits = items.size
                val priorItems = prior[name].orEmpty()
                val priorMonths = priorItems.map { YearMonth.from(it.occurredAt) }.distinct().size
                // Fewer than two months of history is a data point, not a
                // baseline; offering a percentage off it is false precision.
                val baseline = if (priorMonths >= 2)
                    Analytics.round2(priorItems.sumOf { it.amount } / priorMonths) else null
                val change = baseline?.takeIf { it > 0 }
                    ?.let { ((total - it) / it * 100).roundToInt() }
                // Past roughly a tripling a percentage stops being readable —
                // "up 1265%" is a number nobody parses.
                val multiple = if (change != null && change >= 200 && baseline != null)
                    Analytics.round2(total / baseline) else null
                MerchantInsight(name, total, visits,
                    Analytics.round2(if (visits > 0) total / visits else 0.0),
                    baseline, change, multiple, priorItems.size)
            }
    }

    /** Charges far above what that merchant normally costs.
     *
     * Deliberately not a fraud signal — this answers the quieter question
     * "why was this month expensive?", where the answer is usually two or
     * three unusually large but entirely legitimate charges. Uses the MEDIAN
     * because with a mean, one previous spike raises the bar enough to hide
     * the next one — exactly when the user most needs to see it. */
    fun anomalies(txs: List<Tx>, month: YearMonth, limit: Int = 4): List<Anomaly> {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        val histStart = month.minusMonths(6).atDay(1)

        val relevant = txs.filter {
            it.type == "expense" && !it.merchantName.isNullOrBlank() &&
            it.occurredAt.toLocalDate() in histStart..<end
        }
        val history = relevant.filter { it.occurredAt.toLocalDate() < start }
            .groupBy { it.merchantName!! }
        val current = relevant.filter { it.occurredAt.toLocalDate() >= start }

        return current.mapNotNull { t ->
            val past = history[t.merchantName].orEmpty().map { it.amount }
            if (past.size < ANOMALY_MIN_HISTORY) return@mapNotNull null
            val usual = median(past)
            if (usual <= 0 || t.amount < ANOMALY_FLOOR) return@mapNotNull null
            val mult = t.amount / usual
            if (mult < ANOMALY_MULTIPLE) return@mapNotNull null
            Anomaly(t.id, t.merchantName!!, Analytics.round2(t.amount),
                Analytics.round2(usual), Math.round(mult * 10) / 10.0,
                Analytics.round2(t.amount - usual), t.occurredAt.toLocalDate(), past.size)
        }.sortedByDescending { it.excess }.take(limit)
    }

    /** Quantified places money leaks. Never generic advice: every one carries
     *  the sum it is worth, because "consider reducing discretionary
     *  spending" is not something anyone can act on. */
    fun savingsOpportunities(txs: List<Tx>, month: YearMonth,
                             recurring: List<Recurring> = emptyList(),
                             limit: Int = 4,
                             categoryName: (String?) -> String = { it ?: "Uncategorised" }
    ): List<Opportunity> {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        val inMonth = txs.filter { it.type == "expense" &&
            it.occurredAt.toLocalDate() in start..<end }
        val out = mutableListOf<Opportunity>()

        // a) Money promised before the month begins — the number people are
        //    most often surprised by.
        val monthlyEquiv = recurring.sumOf {
            when (it.cadence) {
                "weekly" -> it.amount * 52 / 12
                "monthly" -> it.amount
                "quarterly" -> it.amount / 3
                else -> 0.0
            }
        }
        if (recurring.size >= 2 && monthlyEquiv > 0) {
            out += Opportunity("recurring", Analytics.round2(monthlyEquiv),
                "${recurring.size} recurring charges",
                "About ${money(monthlyEquiv)} a month is committed before you spend " +
                "anything. That is ${money(monthlyEquiv * 12)} a year.",
                recurring.take(4).map { it.name })
        }

        // b) Death by a thousand small payments — individually invisible, and
        //    the single most common surprise in a UPI-era ledger.
        val small = inMonth.filter { it.amount < SMALL_TICKET }
        if (small.size >= SMALL_TICKET_MIN_COUNT) {
            val v = small.sumOf { it.amount }
            out += Opportunity("small", Analytics.round2(v),
                "${small.size} payments under ${money(SMALL_TICKET)}",
                "They add up to ${money(v)} this month — ${money(v / small.size)} at a time.",
                emptyList())
        }

        // c) Categories above the user's OWN recent average. Their history is
        //    the only fair benchmark; a national average is not.
        val base = month.minusMonths(3).atDay(1)
        val priorByCat = txs.filter { it.type == "expense" &&
            it.occurredAt.toLocalDate() in base..<start }
            .groupBy { categoryName(it.categoryId) }
        inMonth.groupBy { categoryName(it.categoryId) }
            .filterKeys { it != "Uncategorised" }   // not a bucket anyone can spend less on
            .mapNotNull { (name, items) ->
                val prior = priorByCat[name].orEmpty()
                val priorMonths = prior.map { YearMonth.from(it.occurredAt) }.distinct().size
                if (priorMonths < 2) return@mapNotNull null
                val avg = prior.sumOf { it.amount } / priorMonths
                val v = items.sumOf { it.amount }
                if (avg <= 0 || v <= avg * 1.25 || (v - avg) < 500) return@mapNotNull null
                Opportunity("category", Analytics.round2(v - avg), "$name is running high",
                    "${money(v)} this month against your own 3-month average of " +
                    "${money(avg)} — ${money(v - avg)} more.", emptyList())
            }
            .sortedByDescending { it.amount }
            .take(2)
            .forEach { out += it }

        return out.sortedByDescending { it.amount }.take(limit)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Indian lakh/crore grouping. A finance app that renders "INR 73103.00"
     *  does not look like one. */
    fun money(value: Double): String {
        val neg = value < 0
        val whole = Math.abs(value).roundToLong().toString()
        val grouped = if (whole.length <= 3) whole else {
            var head = whole.dropLast(3)
            val tail = whole.takeLast(3)
            val parts = mutableListOf<String>()
            while (head.length > 2) { parts.add(0, head.takeLast(2)); head = head.dropLast(2) }
            if (head.isNotEmpty()) parts.add(0, head)
            (parts + tail).joinToString(",")
        }
        return (if (neg) "-₹" else "₹") + grouped
    }
}

data class Forecast(
    val spent: Double, val projected: Double, val runRate: Double,
    val committed: Double, val upcoming: List<Recurring>,
    val elapsedDays: Int, val remainingDays: Int,
    val prevTotal: Double?, val vsPrev: Double?,
)

data class CashFlowMonth(
    val period: YearMonth, val income: Double, val expense: Double,
    val net: Double, val running: Double, val empty: Boolean,
)

data class CategoryTrend(
    val name: String, val series: List<Double>, val months: List<YearMonth>,
    val total: Double, val avg: Double, val direction: String,
    val changePct: Int, val monthsActive: Int,
)

data class MerchantInsight(
    val name: String, val total: Double, val visits: Int, val avgTicket: Double,
    val baseline: Double?, val changePct: Int?, val multiple: Double?,
    val priorVisits: Int,
)

data class Anomaly(
    val id: String, val name: String, val amount: Double, val usual: Double,
    val multiple: Double, val excess: Double, val date: LocalDate,
    val priorCharges: Int,
) {
    val explanation: String
        get() = "${Insights.money(amount)} at $name is ${multiple}x your usual " +
                "${Insights.money(usual)} there ($priorCharges earlier charges)."
}

data class Opportunity(
    val kind: String, val amount: Double, val title: String,
    val detail: String, val items: List<String>,
)
