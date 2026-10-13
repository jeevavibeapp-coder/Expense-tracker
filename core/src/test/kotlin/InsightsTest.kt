import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned clock. An earlier draft of the Python equivalent read the wall
 *  clock and silently skipped whole cases depending on the day of the month. */
private val TODAY = LocalDate.of(2026, 6, 17)
private val MONTH = YearMonth.of(2026, 6)
private var seq = 0

private fun tx(amount: Double, date: LocalDate, merchant: String? = null,
               type: String = "expense", cat: String? = null, hour: Int = 10) =
    Tx("t${seq++}", amount, type, date.atTime(hour, 0), merchant, cat)

fun main() {
    println("=== forecast ===")
    check("a finished month has a total, not a projection",
        Insights.forecast(listOf(tx(500.0, LocalDate.of(2026, 4, 5))),
            YearMonth.of(2026, 4), TODAY) == null)
    check("the first days of a month are too few to project",
        Insights.forecast(listOf(tx(5000.0, LocalDate.of(2026, 6, 1))),
            MONTH, LocalDate.of(2026, 6, 2)) == null)

    val daily = (1..17).map { tx(100.0, LocalDate.of(2026, 6, it)) }
    val f = Insights.forecast(daily, MONTH, TODAY)
    check("a run-rate projects the month", f != null)
    if (f != null) {
        check("run rate is right", Math.abs(f.runRate - 100.0) < 1.0, "rate=${f.runRate}")
        check("projection is about 30 days of it",
            Math.abs(f.projected - 3000.0) <= 100.0, "projected=${f.projected}")
        check("a projection is not printed to the paisa",
            f.projected % 100.0 == 0.0, "projected=${f.projected}")
    }
    val rent = Recurring("Landlord", 9000.0, "monthly", LocalDate.of(2026, 6, 25), 8)
    val withRent = Insights.forecast(daily, MONTH, TODAY, listOf(rent))
    check("rent due later this month is counted as committed",
        withRent != null && Math.abs(withRent.committed - 9000.0) < 1.0,
        "committed=${withRent?.committed}")
    check("and lifts the projection above the bare run-rate",
        withRent != null && f != null && withRent.projected > f.projected)

    println("=== cash flow ===")
    val flowTxs = listOf(
        tx(1000.0, LocalDate.of(2026, 4, 2), type = "income"),
        tx(1200.0, LocalDate.of(2026, 4, 2)),
        tx(1000.0, LocalDate.of(2026, 5, 2), type = "income"),
        tx(1200.0, LocalDate.of(2026, 5, 2)),
        tx(1000.0, LocalDate.of(2026, 6, 2), type = "income"),
        tx(1200.0, LocalDate.of(2026, 6, 2)),
    )
    val flow = Insights.cashFlow(flowTxs, TODAY, 6)
    check("six months are returned", flow.size == 6)
    check("quiet months are zero-filled, not skipped", flow.count { it.empty } == 3)
    check("periods are consecutive and ascending",
        flow.zipWithNext().all { (a, b) -> a.period.plusMonths(1) == b.period })
    var running = 0.0
    check("the running total accumulates",
        flow.all { running += it.net; Math.abs(it.running - running) < 0.01 })
    check("consistent overspend shows as negative",
        flow.last { !it.empty }.running < 0)

    println("=== category trends ===")
    val twoMonths = listOf(tx(5000.0, LocalDate.of(2026, 5, 3), cat = "c1"),
                           tx(5000.0, LocalDate.of(2026, 6, 3), cat = "c1"))
    check("two heavy months in a row is not a trend",
        Insights.categoryTrends(twoMonths, TODAY) { "Food" }.all { it.direction == "steady" })
    val rising = listOf(1000.0 to 1, 1000.0 to 2, 1000.0 to 3,
                        4000.0 to 4, 4000.0 to 5, 4000.0 to 6)
        .map { (amt, m) -> tx(amt, LocalDate.of(2026, m, 3), cat = "c1") }
    val t = Insights.categoryTrends(rising, TODAY) { "Food" }.firstOrNull { it.name == "Food" }
    check("a real rise is detected", t?.direction == "rising", "got=${t?.direction}")
    check("and quantified", (t?.changePct ?: 0) > 50)
    check("uncategorised stays visible — it is usually the biggest bucket",
        Insights.categoryTrends(listOf(tx(2500.0, TODAY)), TODAY).any { it.name == "Uncategorised" })

    println("=== merchants are compared against themselves ===")
    val mtx = mutableListOf<Tx>()
    for (m in 3..5) {
        mtx += tx(1000.0, LocalDate.of(2026, m, 5), "Cafe")
        mtx += tx(40000.0, LocalDate.of(2026, m, 5), "Landlord")
    }
    mtx += tx(2000.0, LocalDate.of(2026, 6, 1), "Cafe")
    mtx += tx(40000.0, LocalDate.of(2026, 6, 1), "Landlord")
    val byName = Insights.merchantInsights(mtx, MONTH).associateBy { it.name }
    check("a cafe that doubled says so",
        Math.abs((byName["Cafe"]?.changePct ?: 0) - 100) <= 2, "got=${byName["Cafe"]?.changePct}")
    check("a flat landlord is not flagged just for being large",
        Math.abs(byName["Landlord"]?.changePct ?: 0) <= 2)
    val oneMonth = listOf(tx(500.0, LocalDate.of(2026, 5, 4), "Cafe"),
                          tx(900.0, LocalDate.of(2026, 6, 1), "Cafe"))
    check("one prior month is not a baseline",
        Insights.merchantInsights(oneMonth, MONTH).first().baseline == null)
    val explosive = (3..5).map { tx(500.0, LocalDate.of(2026, it, 4), "Swiggy") } +
                    tx(7000.0, LocalDate.of(2026, 6, 1), "Swiggy")
    val ex = Insights.merchantInsights(explosive, MONTH).first()
    check("extreme growth is told as a multiple, not '1265%'", ex.multiple != null,
          "multiple=${ex.multiple} pct=${ex.changePct}")

    println("=== anomalies ===")
    check("too little history means no claim",
        Insights.anomalies(listOf(tx(200.0, LocalDate.of(2026, 5, 2), "Shop"),
                                  tx(5000.0, LocalDate.of(2026, 6, 1), "Shop")), MONTH).isEmpty())
    val spikey = listOf(400.0 to 2, 400.0 to 5, 400.0 to 8, 400.0 to 12, 9000.0 to 15)
        .map { (a, d) -> tx(a, LocalDate.of(2026, 5, d), "Shop") } +
        tx(4000.0, LocalDate.of(2026, 6, 1), "Shop")
    val found = Insights.anomalies(spikey, MONTH)
    check("the median stops one past spike hiding the next", found.isNotEmpty())
    if (found.isNotEmpty()) {
        check("usual is the median, not the mean",
            Math.abs(found[0].usual - 400.0) < 1.0, "usual=${found[0].usual}")
        check("the multiple is right", Math.abs(found[0].multiple - 10.0) < 0.2)
        check("the explanation quotes the numbers behind it",
            found[0].explanation.contains("Shop") && found[0].explanation.contains("10.0x"))
    }
    val tea = (2..5).map { tx(20.0, LocalDate.of(2026, 5, it), "Tea") } +
              tx(60.0, LocalDate.of(2026, 6, 1), "Tea")
    check("a small multiple of a small number is noise, not an anomaly",
        Insights.anomalies(tea, MONTH).isEmpty())

    println("=== savings opportunities ===")
    check("an empty ledger is told nothing",
        Insights.savingsOpportunities(emptyList(), MONTH).isEmpty())
    val many = (1..12).map { tx(150.0, LocalDate.of(2026, 6, 1), "M$it") }
    val small = Insights.savingsOpportunities(many, MONTH).firstOrNull { it.kind == "small" }
    check("small payments are totalled", small != null)
    check("with the real sum", small != null && Math.abs(small.amount - 1800.0) < 1.0)
    check("below the count gate it stays quiet",
        Insights.savingsOpportunities((1..3).map { tx(150.0, LocalDate.of(2026, 6, 1), "M$it") },
            MONTH).none { it.kind == "small" })
    val overspend = (3..5).map { tx(2000.0, LocalDate.of(2026, it, 4), cat = "c1") } +
                    tx(6000.0, LocalDate.of(2026, 6, 1), cat = "c1")
    val cat = Insights.savingsOpportunities(overspend, MONTH) { if (it == null) "Uncategorised" else "Food" }
        .firstOrNull { it.kind == "category" }
    check("a 3x category month is surfaced", cat != null, "got=null")
    check("measured against the user's own average",
        cat != null && cat.detail.contains("3-month average"))
    check("uncategorised is never an opportunity — nobody can spend less on it",
        Insights.savingsOpportunities(overspend, MONTH).none { it.title.contains("Uncategorised") })
    check("every opportunity carries a number",
        Insights.savingsOpportunities(many, MONTH).all {
            it.amount > 0 && it.detail.any { c -> c.isDigit() } })

    println("=== recurring, transfers, refunds ===")
    val monthly = (1..4).map { tx(499.0, LocalDate.of(2026, it + 2, 10), "Netflix") }
    val rec = Analytics.detectRecurring(monthly, TODAY)
    check("a monthly charge is detected", rec.any { it.name == "Netflix" })
    check("with a cadence", rec.firstOrNull()?.cadence == "monthly")
    check("two purchases are not a subscription",
        Analytics.detectRecurring(listOf(tx(499.0, LocalDate.of(2026, 5, 10), "Shop"),
                                         tx(499.0, LocalDate.of(2026, 6, 10), "Shop")),
            TODAY).isEmpty())
    check("a wildly varying amount is not a subscription",
        Analytics.detectRecurring((1..4).map {
            tx(100.0 * it * it, LocalDate.of(2026, it + 2, 10), "Shop") }, TODAY).isEmpty())

    val selfTransfer = listOf(
        tx(5000.0, LocalDate.of(2026, 6, 10), "Own", hour = 10),
        tx(5000.0, LocalDate.of(2026, 6, 10), "Own", type = "income", hour = 10))
    check("a debit and credit minutes apart is money that never left",
        Analytics.detectTransfers(selfTransfer).size == 1)
    check("different amounts are not a transfer",
        Analytics.detectTransfers(listOf(
            tx(5000.0, LocalDate.of(2026, 6, 10), "Own"),
            tx(4000.0, LocalDate.of(2026, 6, 10), "Own", type = "income"))).isEmpty())
    check("hours apart is not a transfer",
        Analytics.detectTransfers(listOf(
            tx(5000.0, LocalDate.of(2026, 6, 10), "Own", hour = 2),
            tx(5000.0, LocalDate.of(2026, 6, 10), "Own", type = "income", hour = 22))).isEmpty())

    val refund = listOf(
        tx(2000.0, LocalDate.of(2026, 6, 1), "Amazon"),
        tx(2000.0, LocalDate.of(2026, 6, 10), "Amazon", type = "income", hour = 11))
    check("a credit from a merchant you paid is a refund",
        Analytics.detectRefunds(refund).size == 1)
    check("a refund cannot exceed what was paid",
        Analytics.detectRefunds(listOf(
            tx(500.0, LocalDate.of(2026, 6, 1), "Amazon"),
            tx(9000.0, LocalDate.of(2026, 6, 10), "Amazon", type = "income"))).isEmpty())

    val flowResult = Analytics.moneyFlow(selfTransfer)
    check("a self-transfer is counted as neither spending nor income",
        flowResult.income == 0.0 && flowResult.expense == 0.0,
        "income=${flowResult.income} expense=${flowResult.expense}")
    val refundFlow = Analytics.moneyFlow(refund)
    check("a refund reduces spending instead of inflating income",
        refundFlow.income == 0.0 && refundFlow.expense == 0.0,
        "income=${refundFlow.income} expense=${refundFlow.expense}")

    println("=== money formatting is Indian ===")
    check("lakh grouping", Insights.money(73103.0) == "₹73,103", Insights.money(73103.0))
    check("crore grouping", Insights.money(12345678.0) == "₹1,23,45,678", Insights.money(12345678.0))
    check("small numbers unchanged", Insights.money(450.0) == "₹450")
    check("negatives", Insights.money(-450.0) == "-₹450")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
