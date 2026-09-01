package com.jeevavibeapp.spendwise.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Budgets, per-category aggregation, daily bars and the monthly report.
 * Pure Kotlin over a list of transactions — the caller does the querying.
 *
 * The rule this file is written to: a number the user is measured against
 * must never be softened. A budget that has been blown says so and by how
 * much; a category with no budget says nothing rather than something
 * comfortable; a day with no spending is a zero, not a gap in the data.
 */
object Budgets {

    /** Amber starts here. Below it a bar is just progress, not a warning. */
    const val NEAR_LIMIT_PCT = 80
    const val DEFAULT_SERIES_DAYS = 14
    const val UNCATEGORISED = "Uncategorised"
    const val DEFAULT_COLOR = "#6366f1"

    /** Spend so far this month per category id. Transactions with no
     *  category are excluded: they belong to no budget. */
    fun monthCategorySpend(txs: List<Tx>, month: YearMonth,
                           today: LocalDate): Map<String, Double> {
        val start = month.atDay(1)
        val end = soFar(month, today)
        return txs
            .filter {
                it.type == "expense" && it.categoryId != null &&
                it.occurredAt.toLocalDate() in start..<end
            }
            .groupBy { it.categoryId!! }
            .mapValues { (_, rows) -> Analytics.round2(rows.sumOf { it.amount }) }
    }

    /** Progress against each budget, over-limit ones first.
     *
     * Categories the user has not budgeted are still returned when they have
     * spending, with a null percentage — see [BudgetStatus.pct]. */
    fun budgetStatus(txs: List<Tx>, budgets: List<Budget>, month: YearMonth,
                     today: LocalDate): List<BudgetStatus> {
        val spent = monthCategorySpend(txs, month, today)
        val rows = budgets.mapNotNull { b ->
            val s = spent[b.categoryId] ?: 0.0
            // Zero and negative are not limits anyone can be measured
            // against, so they mean "unbudgeted" rather than "already over".
            val limit = b.amount?.takeIf { it > 0 }
            if (limit == null) {
                if (s <= 0) return@mapNotNull null
                BudgetStatus(
                    categoryId = b.categoryId, name = b.name, color = b.color,
                    budget = null, spent = Analytics.round2(s), pct = null,
                    remaining = null, overBy = null, fraction = 0.0,
                    state = "unbudgeted",
                )
            } else {
                // Not clamped at 100. Someone 40% over their food budget is
                // in a different situation from someone exactly on it, and a
                // capped bar tells them both the same thing.
                val pct = (s / limit * 100).roundToInt()
                val over = s > limit
                BudgetStatus(
                    categoryId = b.categoryId, name = b.name, color = b.color,
                    budget = Analytics.round2(limit), spent = Analytics.round2(s),
                    pct = pct,
                    remaining = Analytics.round2(limit - s),
                    overBy = if (over) Analytics.round2(s - limit) else 0.0,
                    // The bar cannot draw past its own width; pct carries the
                    // truth the bar has to drop.
                    fraction = (s / limit).coerceIn(0.0, 1.0),
                    state = when {
                        over -> "over"
                        pct >= NEAR_LIMIT_PCT -> "near"
                        else -> "ok"
                    },
                )
            }
        }
        // An unbudgeted category has no percentage, so it cannot hold a place
        // in a ranking by percentage: sorting it as 0% buries it below every
        // budget, and as 100% it displaces a real overspend.
        val (tracked, untracked) = rows.partition { it.pct != null }
        return tracked.sortedByDescending { it.pct } +
               untracked.sortedByDescending { it.spent }
    }

    /** Expense totals per day for the last N days, ending today.
     *
     * Zero-filled. A day with no spending is a fact worth drawing — drop it
     * and the chart silently closes the gap, so the next day's bar appears
     * where the quiet one was and a week of six spending days looks like
     * seven. */
    fun dailySeries(txs: List<Tx>, today: LocalDate,
                    days: Int = DEFAULT_SERIES_DAYS): List<DaySpend> {
        if (days <= 0) return emptyList()
        val start = today.minusDays((days - 1).toLong())
        val byDay = txs
            .filter { it.type == "expense" && it.occurredAt.toLocalDate() in start..today }
            .groupBy { it.occurredAt.toLocalDate() }
        return (0 until days).map { i ->
            val d = start.plusDays(i.toLong())
            DaySpend(d, Analytics.round2(byDay[d]?.sumOf { it.amount } ?: 0.0))
        }
    }

    /** Current no-spend streak and no-spend days this month.
     *
     * A streak is a count of days SINCE the last time money went out. On a
     * ledger with no spending at all there is no "since" and nothing has
     * been achieved, so it is zero — the Python original walked back to the
     * edge of its 60-day window and congratulated a brand-new install on a
     * 60-day streak. [NoSpendStats.hasHistory] is what separates "you have
     * not spent" from "we have not seen anything yet". */
    fun noSpendStats(txs: List<Tx>, today: LocalDate): NoSpendStats {
        val spendDays = txs
            .filter { it.type == "expense" }
            .map { it.occurredAt.toLocalDate() }
            .filter { !it.isAfter(today) }
            .toSet()
        val last = spendDays.maxOrNull() ?: return NoSpendStats(0, 0, false)
        return NoSpendStats(
            streak = ChronoUnit.DAYS.between(last, today).toInt(),
            monthFreeDays = (1..today.dayOfMonth).count { today.withDayOfMonth(it) !in spendDays },
            hasHistory = true,
        )
    }

    /** Everything the monthly report screen shows for one month.
     *
     * [categories] supplies the names and colours; a transaction whose
     * category is missing or archived away falls into [UNCATEGORISED] rather
     * than vanishing, because the category lines are meant to add up to the
     * headline expense and a silently dropped bucket is what stops them. */
    fun buildReport(txs: List<Tx>, month: YearMonth, today: LocalDate,
                    categories: List<Budget> = emptyList()): MonthReport {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        val prev = month.minusMonths(1)
        val prevStart = prev.atDay(1)
        val isCurrent = today in start..<end

        val inMonth = txs.filter { it.occurredAt.toLocalDate() in start..<end }
        val income = inMonth.filter { it.type == "income" }.sumOf { it.amount }
        val expense = inMonth.filter { it.type == "expense" }.sumOf { it.amount }

        // Compare like with like: a month in progress is measured against the
        // same span of the previous month, not against all of it. Otherwise
        // the report reads "spending is down" every month until its last day.
        val prevEnd = if (isCurrent)
            minOf(prevStart.plusDays(today.dayOfMonth.toLong()), start) else start
        val prevRows = txs.filter { it.occurredAt.toLocalDate() in prevStart..<prevEnd }
        val prevExpense = prevRows.filter { it.type == "expense" }.sumOf { it.amount }

        val nameOf = categories.associateBy({ it.categoryId }, { it.name })
        val colorOf = categories.associateBy({ it.name }, { it.color })
        val now = spendByCategory(inMonth, nameOf)
        // Deltas share the truncated bound above so the per-category changes
        // still account for the headline change.
        val before = spendByCategory(prevRows, nameOf)
        val lines = (now.keys + before.keys).mapNotNull { name ->
            val v = now[name] ?: 0.0
            val p = before[name] ?: 0.0
            // A category that was quiet in both months is not a line.
            if (v <= 0 && p <= 0) return@mapNotNull null
            CategoryLine(name, colorOf[name] ?: DEFAULT_COLOR,
                Analytics.round2(v), Analytics.round2(v - p))
        }.sortedByDescending { it.value }

        val merchants = inMonth
            .filter { it.type == "expense" && !it.merchantName.isNullOrBlank() }
            .groupBy { it.merchantName!! }
            .map { (n, rows) -> MerchantLine(n, Analytics.round2(rows.sumOf { it.amount })) }
            .sortedByDescending { it.value }
            .take(5)

        // Days that have not happened yet are not no-spend days.
        val elapsed = if (isCurrent) today.dayOfMonth else month.lengthOfMonth()
        val byDay = inMonth.filter { it.type == "expense" }
            .groupBy { it.occurredAt.toLocalDate() }
        val daily = (1..elapsed).map { d ->
            val date = month.atDay(d)
            DaySpend(date, Analytics.round2(byDay[date]?.sumOf { it.amount } ?: 0.0))
        }

        return MonthReport(
            month = month,
            label = "${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}",
            prevMonth = prev,
            nextMonth = month.plusMonths(1),
            income = Analytics.round2(income),
            expense = Analytics.round2(expense),
            saved = Analytics.round2(income - expense),
            // Without income there is nothing to have saved a share of, and
            // "0% saved" would read as a judgement rather than as no answer.
            saveRate = if (income > 0) ((income - expense) / income * 100).roundToInt() else null,
            prevExpense = Analytics.round2(prevExpense),
            expenseDelta = Analytics.round2(expense - prevExpense),
            categories = lines,
            merchants = merchants,
            daily = daily,
            highestDay = daily.maxByOrNull { it.amount }?.takeIf { it.amount > 0 },
            noSpendDays = daily.count { it.amount == 0.0 },
            txCount = inMonth.size,
        )
    }

    private fun spendByCategory(rows: List<Tx>, nameOf: Map<String, String>): Map<String, Double> =
        rows.filter { it.type == "expense" }
            .groupBy { it.categoryId?.let(nameOf::get) ?: UNCATEGORISED }
            .mapValues { (_, items) -> items.sumOf { it.amount } }

    /** Upper bound for "so far this month": tomorrow, or the month's end.
     *
     * A rent debit dated the 30th must not show the budget as blown on the
     * 2nd — it is money committed, not money already spent. */
    private fun soFar(month: YearMonth, today: LocalDate): LocalDate =
        minOf(month.plusMonths(1).atDay(1), today.plusDays(1))
}

/** A category, with the monthly limit the user set on it if they set one.
 *
 * [amount] is null for most categories: budgeting one category does not
 * commit the user to budgeting all of them, and the difference between "no
 * limit" and "a limit of zero" is the whole point of the type. */
data class Budget(
    val categoryId: String,
    val name: String,
    val amount: Double? = null,
    val color: String = Budgets.DEFAULT_COLOR,
)

/**
 * One category's standing this month.
 *
 * [pct] is null when no budget is set — that is not the same as 0% (nothing
 * spent) or 100% (exactly at the limit), and reporting it as either invents
 * a limit the user never agreed to. It is not capped above 100 either: being
 * over is the number that matters most, and [overBy] says by how much.
 */
data class BudgetStatus(
    val categoryId: String,
    val name: String,
    val color: String,
    val budget: Double?,
    val spent: Double,
    val pct: Int?,
    /** Negative once overspent — the same fact as [overBy], from the side
     *  the user was looking at before they crossed it. */
    val remaining: Double?,
    val overBy: Double?,
    /** 0..1, for drawing only. */
    val fraction: Double,
    val state: String,          // unbudgeted | ok | near | over
)

data class DaySpend(val date: LocalDate, val amount: Double)

data class NoSpendStats(
    val streak: Int,
    val monthFreeDays: Int,
    /** False when the ledger records no spending at all, so the UI can stay
     *  quiet instead of celebrating an empty database. */
    val hasHistory: Boolean,
)

data class CategoryLine(
    val name: String, val color: String, val value: Double,
    /** Change against the same span of the previous month. */
    val delta: Double,
)

data class MerchantLine(val name: String, val value: Double)

data class MonthReport(
    val month: YearMonth,
    val label: String,
    val prevMonth: YearMonth,
    val nextMonth: YearMonth,
    val income: Double,
    val expense: Double,
    val saved: Double,
    val saveRate: Int?,
    val prevExpense: Double,
    val expenseDelta: Double,
    val categories: List<CategoryLine>,
    val merchants: List<MerchantLine>,
    val daily: List<DaySpend>,
    val highestDay: DaySpend?,
    val noSpendDays: Int,
    val txCount: Int,
)
