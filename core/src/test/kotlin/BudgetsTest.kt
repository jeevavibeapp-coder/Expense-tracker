import com.jeevavibeapp.spendwise.core.Budget
import com.jeevavibeapp.spendwise.core.Budgets
import com.jeevavibeapp.spendwise.core.Tx
import java.time.LocalDate
import java.time.YearMonth

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned clock. Reading the wall clock here would make half these cases
 *  silently stop testing anything depending on the day of the month. */
private val TODAY = LocalDate.of(2026, 6, 17)
private val MONTH = YearMonth.of(2026, 6)
private var seq = 0

private fun tx(amount: Double, date: LocalDate, cat: String? = null,
               merchant: String? = null, type: String = "expense") =
    Tx("b${seq++}", amount, type, date.atTime(10, 0), merchant, cat)

private fun june(day: Int) = LocalDate.of(2026, 6, day)
private fun may(day: Int) = LocalDate.of(2026, 5, day)

fun main() {
    println("=== month boundaries ===")
    val straddle = listOf(tx(500.0, may(31), cat = "c1"), tx(700.0, june(1), cat = "c1"))
    check("a transaction on the 1st belongs to that month",
        Budgets.monthCategorySpend(straddle, MONTH, TODAY)["c1"] == 700.0,
        "${Budgets.monthCategorySpend(straddle, MONTH, TODAY)}")
    check("and the last day of the previous month stays in the previous month",
        Budgets.monthCategorySpend(straddle, YearMonth.of(2026, 5), TODAY)["c1"] == 500.0)
    check("the report puts the 1st in its own month too",
        Budgets.buildReport(straddle, MONTH, TODAY).expense == 700.0)
    check("a bill dated later this month is not spent yet",
        Budgets.monthCategorySpend(listOf(tx(9000.0, june(25), cat = "c1")), MONTH, TODAY)
            .isEmpty())
    check("income is not spending",
        Budgets.monthCategorySpend(listOf(tx(9000.0, june(2), cat = "c1", type = "income")),
            MONTH, TODAY).isEmpty())

    println("=== budget progress ===")
    val budgets = listOf(
        Budget("c-food", "Food", 10000.0, "#ff0000"),
        Budget("c-fuel", "Fuel", 5000.0),
        Budget("c-rent", "Rent", 20000.0),
        Budget("c-misc", "Misc"),
        Budget("c-none", "Untouched"),
    )
    val spendTxs = listOf(
        tx(9000.0, june(3), cat = "c-food"),
        tx(5000.0, june(10), cat = "c-food"),
        tx(4000.0, june(5), cat = "c-fuel"),
        tx(2500.0, june(6), cat = "c-misc"),
    )
    val status = Budgets.budgetStatus(spendTxs, budgets, MONTH, TODAY)
    val byName = status.associateBy { it.name }
    val food = byName["Food"]
    check("progress is a percentage of the budget", food?.pct == 140, "pct=${food?.pct}")
    check("overspend is not clamped away at 100%", (food?.pct ?: 0) > 100)
    check("and is stated as an amount", food?.overBy == 4000.0, "overBy=${food?.overBy}")
    check("what is left goes negative rather than resting at zero",
        food?.remaining == -4000.0, "remaining=${food?.remaining}")
    check("the bar still fits the bar", food?.fraction == 1.0, "fraction=${food?.fraction}")
    check("being over is called over", food?.state == "over")
    check("four fifths in is a warning, not yet a breach",
        byName["Fuel"]?.pct == 80 && byName["Fuel"]?.state == "near",
        "pct=${byName["Fuel"]?.pct} state=${byName["Fuel"]?.state}")
    check("an untouched budget is at zero, not missing",
        byName["Rent"]?.pct == 0 && byName["Rent"]?.state == "ok")
    check("the colour the user chose survives", food?.color == "#ff0000")

    println("=== a category with no budget makes no claim ===")
    val misc = byName["Misc"]
    check("its spend is still reported", misc?.spent == 2500.0)
    check("but it has no percentage", misc?.pct == null, "pct=${misc?.pct}")
    check("not 0% — that would read as untouched", misc?.pct != 0)
    check("not 100% — that would invent a limit", misc?.pct != 100)
    check("and no budget figure is conjured for it", misc?.budget == null)
    check("it says what it is", misc?.state == "unbudgeted")
    check("a category with neither a budget nor spending is not a row",
        byName["Untouched"] == null)
    check("over-limit budgets come first",
        status.firstOrNull()?.name == "Food", "first=${status.firstOrNull()?.name}")
    check("an unbudgeted category never outranks a real budget",
        status.indexOf(misc) > status.indexOf(byName["Rent"]),
        "order=${status.map { it.name }}")

    println("=== daily series is zero-filled ===")
    val bars = Budgets.dailySeries(listOf(
        tx(300.0, june(15)), tx(200.0, june(15)), tx(700.0, june(17)),
        tx(9000.0, june(14), type = "income")), TODAY, days = 5)
    check("exactly the days asked for", bars.size == 5)
    check("ending today", bars.lastOrNull()?.date == TODAY)
    check("consecutive and ascending",
        bars.zipWithNext().all { (a, b) -> a.date.plusDays(1) == b.date })
    check("a quiet day is a zero, not a missing bar",
        bars.any { it.date == june(14) && it.amount == 0.0 },
        "${bars.map { it.date.dayOfMonth to it.amount }}")
    check("income does not fill a quiet day",
        bars.firstOrNull { it.date == june(14) }?.amount == 0.0)
    check("a day's transactions are summed",
        bars.firstOrNull { it.date == june(15) }?.amount == 500.0)
    check("three of these five days had no spending",
        bars.count { it.amount == 0.0 } == 3)
    check("an empty ledger still draws its days",
        Budgets.dailySeries(emptyList(), TODAY, days = 7).size == 7 &&
        Budgets.dailySeries(emptyList(), TODAY, days = 7).all { it.amount == 0.0 })

    println("=== a streak has to be earned ===")
    val fresh = Budgets.noSpendStats(emptyList(), TODAY)
    check("a brand-new install claims no streak", fresh.streak == 0, "streak=${fresh.streak}")
    check("and says why: there is no history", !fresh.hasHistory)
    check("nor does it claim a month of no-spend days", fresh.monthFreeDays == 0,
        "monthFree=${fresh.monthFreeDays}")
    val incomeOnly = Budgets.noSpendStats(
        listOf(tx(50000.0, june(2), type = "income")), TODAY)
    check("a ledger with income but no spending is still no history",
        incomeOnly.streak == 0 && !incomeOnly.hasHistory)
    val spentToday = Budgets.noSpendStats(listOf(tx(100.0, TODAY)), TODAY)
    check("spending today ends the streak", spentToday.streak == 0)
    check("but that ledger does have history", spentToday.hasHistory)
    val quiet = Budgets.noSpendStats(listOf(tx(100.0, june(14))), TODAY)
    check("three days since the last spend is a streak of three", quiet.streak == 3,
        "streak=${quiet.streak}")
    check("the no-spend days of this month are counted", quiet.monthFreeDays == 16,
        "monthFree=${quiet.monthFreeDays}")
    val future = Budgets.noSpendStats(
        listOf(tx(100.0, june(14)), tx(9000.0, june(25))), TODAY)
    check("a future-dated bill does not end a streak that is still running",
        future.streak == 3, "streak=${future.streak}")

    println("=== the monthly report ===")
    val cats = listOf(
        Budget("c-food", "Food", 10000.0, "#ff0000"),
        Budget("c-fuel", "Fuel", 5000.0),
        Budget("c-misc", "Misc"),
        Budget("c-trav", "Travel"),
    )
    val ledger = listOf(
        tx(50000.0, may(1), type = "income"),
        tx(8000.0, may(3), cat = "c-food"),
        tx(1200.0, may(4), cat = "c-trav"),
        tx(3000.0, may(20), cat = "c-fuel"),          // after the like-for-like cut
        tx(50000.0, june(2), type = "income"),
        tx(9000.0, june(3), cat = "c-food", merchant = "BigBasket"),
        tx(4000.0, june(5), cat = "c-fuel", merchant = "Shell"),
        tx(2500.0, june(6), cat = "c-misc", merchant = "Amazon"),
        tx(800.0, june(8), merchant = "Chai Point"),
        tx(100.0, june(9), merchant = "Kirana"),
        tx(5000.0, june(10), cat = "c-food", merchant = "Zomato"),
    )
    val r = Budgets.buildReport(ledger, MONTH, TODAY, cats)
    check("the month is named", r.label == "June 2026", r.label)
    check("and knows its neighbours",
        r.prevMonth == YearMonth.of(2026, 5) && r.nextMonth == YearMonth.of(2026, 7))
    check("income is this month's income", r.income == 50000.0, "${r.income}")
    check("expense is this month's expense", r.expense == 21400.0, "${r.expense}")
    check("saved is the difference", r.saved == 28600.0, "${r.saved}")
    check("and a savings rate off it", r.saveRate == 57, "${r.saveRate}")
    check("no income means no savings rate to state",
        Budgets.buildReport(listOf(tx(500.0, june(3))), MONTH, TODAY).saveRate == null)
    check("a month in progress is compared with the same span of the last one",
        r.prevExpense == 9200.0, "prevExpense=${r.prevExpense}")
    check("so the headline change is like for like", r.expenseDelta == 12200.0,
        "${r.expenseDelta}")

    check("category lines add up to the headline expense",
        Math.abs(r.categories.sumOf { it.value } - r.expense) < 0.01,
        "${r.categories.map { it.name to it.value }}")
    check("per-category deltas account for the headline change",
        Math.abs(r.categories.sumOf { it.delta } - r.expenseDelta) < 0.01,
        "${r.categories.map { it.name to it.delta }}")
    check("uncategorised spending is shown, not quietly dropped",
        r.categories.any { it.name == "Uncategorised" && it.value == 900.0 },
        "${r.categories.map { it.name to it.value }}")
    val travel = r.categories.firstOrNull { it.name == "Travel" }
    check("a category that stopped still shows its fall",
        travel != null && travel.value == 0.0 && travel.delta == -1200.0,
        "travel=$travel")
    check("biggest category first", r.categories.firstOrNull()?.name == "Food")
    check("categories carry their colour", r.categories.firstOrNull()?.color == "#ff0000")

    check("top merchants are capped at five", r.merchants.size == 5)
    check("biggest first", r.merchants.firstOrNull()?.name == "BigBasket")
    check("the smallest merchant is the one dropped",
        r.merchants.none { it.name == "Kirana" })

    check("daily bars cover the elapsed days only", r.daily.size == 17, "${r.daily.size}")
    check("starting on the 1st", r.daily.firstOrNull()?.date == june(1))
    check("and ending today", r.daily.lastOrNull()?.date == TODAY)
    check("the heaviest day is found",
        r.highestDay?.date == june(3) && r.highestDay?.amount == 9000.0, "${r.highestDay}")
    check("quiet days are counted", r.noSpendDays == 11, "${r.noSpendDays}")
    check("transactions are counted", r.txCount == 7, "${r.txCount}")

    val empty = Budgets.buildReport(emptyList(), MONTH, TODAY)
    check("an empty month has no heaviest day to point at", empty.highestDay == null)
    check("an empty month has no category lines", empty.categories.isEmpty())
    check("but it still has its days", empty.daily.size == 17 && empty.noSpendDays == 17)

    val finished = Budgets.buildReport(ledger, YearMonth.of(2026, 5), TODAY, cats)
    check("a finished month is drawn to its last day", finished.daily.size == 31,
        "${finished.daily.size}")
    check("and counts all of its spending", finished.expense == 12200.0,
        "${finished.expense}")
    check("a finished month is compared with all of the month before it",
        finished.prevExpense == 0.0, "${finished.prevExpense}")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
