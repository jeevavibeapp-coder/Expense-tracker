import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDate
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned clock, like every other suite here: a detector that compares a
 *  charge against "days before it" changes answer with the calendar. */
private val TODAY = LocalDate.of(2026, 6, 17)
private var seq = 0

private fun tx(amount: Double, at: LocalDateTime, merchant: String? = null,
               type: String = "expense") =
    Tx("f${seq++}", amount, type, at, merchant, null)

private fun at(day: Int, hour: Int = 10, minute: Int = 0): LocalDateTime =
    LocalDate.of(2026, 6, day).atTime(hour, minute)

/** Ten ordinary charges on ten separate days, all at one payee. Enough to
 *  clear MIN_HISTORY, flat enough to raise nothing by itself. */
private fun quietHistory(amount: Double = 500.0, merchant: String? = "Shop A") =
    (1..10).map { tx(amount, at(it), merchant) }

private fun kinds(alerts: List<FraudAlert>) = alerts.map { it.kind }.sorted()

fun main() {
    println("=== a quiet ledger raises nothing ===")
    val quiet = quietHistory()
    check("an ordinary charge on an ordinary day is silent",
        Fraud.evaluate(tx(500.0, at(17), "Shop A"), quiet).isEmpty(),
        kinds(Fraud.evaluate(tx(500.0, at(17), "Shop A"), quiet)).toString())
    check("income is never evaluated at all",
        Fraud.evaluate(tx(900000.0, at(17), "Payroll", type = "income"), quiet,
            highValueLimit = 1000.0).isEmpty())

    println("=== duplicate charges ===")
    val charge = tx(500.0, at(17, 10, 0), "Swiggy")
    val again = tx(500.0, at(17, 10, 4), "Swiggy")
    check("the same amount at the same payee minutes apart is flagged",
        Fraud.DUPLICATE in kinds(Fraud.evaluate(charge, quiet + again)))
    check("half an hour later is not a duplicate",
        Fraud.DUPLICATE !in kinds(Fraud.evaluate(
            charge, quiet + tx(500.0, at(17, 10, 34), "Swiggy"))))
    check("the same amount at a different payee is not a duplicate",
        Fraud.DUPLICATE !in kinds(Fraud.evaluate(
            charge, quiet + tx(500.0, at(17, 10, 4), "Zomato"))))
    check("a different amount at the same payee is not a duplicate",
        Fraud.DUPLICATE !in kinds(Fraud.evaluate(
            charge, quiet + tx(560.0, at(17, 10, 4), "Swiggy"))))
    // The Python detector compares amounts and times with no type filter, so
    // both of these reported a duplicate charge that never happened.
    check("a refund of the same amount from the same payee is not a duplicate charge",
        Fraud.DUPLICATE !in kinds(Fraud.evaluate(
            charge, quiet + tx(500.0, at(17, 10, 4), "Swiggy", type = "income"))))
    check("a credit landing beside an unnamed charge is not a duplicate either",
        Fraud.DUPLICATE !in kinds(Fraud.evaluate(
            tx(500.0, at(17, 10, 0)), quiet + tx(500.0, at(17, 10, 4), type = "income"))))
    check("a capture with no resolved payee still matches on amount and time",
        Fraud.DUPLICATE in kinds(Fraud.evaluate(
            tx(500.0, at(17, 10, 0)), quiet + tx(500.0, at(17, 10, 4), "Swiggy"))))

    println("=== high-value outliers against the user's own distribution ===")
    val varied = (1..10).map { tx(400.0 + it * 20, at(it), "Shop A") }
    val spike = Fraud.evaluate(tx(12000.0, at(17), "Shop A"), varied)
    check("a charge far outside the spread is flagged", Fraud.OUTLIER in kinds(spike))
    check("and it is the high-severity one",
        spike.first { it.kind == Fraud.OUTLIER }.severity == Fraud.HIGH)
    check("the message names the average it was measured against",
        spike.first { it.kind == Fraud.OUTLIER }.message.contains("average charge"),
        spike.first { it.kind == Fraud.OUTLIER }.message)
    check("five charges are too little history to call anything unusual",
        Fraud.evaluate(tx(12000.0, at(17), "Shop A"),
            (1..5).map { tx(500.0, at(it), "Shop A") }).isEmpty())
    // A flat history has no spread, and the Python original substitutes 1.0
    // for the standard deviation there — which makes any charge a few rupees
    // above a run of identical ones a "high-value" alert.
    check("a ledger of identical amounts does not make a small overshoot an outlier",
        Fraud.OUTLIER !in kinds(Fraud.evaluate(
            tx(62.0, at(17), "Chai"), (1..10).map { tx(50.0, at(it), "Chai") })))
    val tight = (1..4).flatMap { listOf(tx(100.0, at(it), "Shop A"), tx(101.0, at(it), "Shop A")) }
    check("a tiny excess is noise however many sigma it measures",
        Fraud.OUTLIER !in kinds(Fraud.evaluate(tx(105.0, at(17), "Shop A"), tight)),
        kinds(Fraud.evaluate(tx(105.0, at(17), "Shop A"), tight)).toString())

    println("=== the high-value amount the user typed ===")
    val big = Fraud.evaluate(tx(30000.0, at(17), "Jeweller"), emptyList(), highValueLimit = 25000.0)
    check("the limit is honoured with no history at all",
        Fraud.OVER_LIMIT in kinds(big))
    check("and it is high severity",
        big.first { it.kind == Fraud.OVER_LIMIT }.severity == Fraud.HIGH)
    check("the message quotes the limit back, so the promise is checkable",
        big.first { it.kind == Fraud.OVER_LIMIT }.message.contains(Insights.money(25000.0)),
        big.first { it.kind == Fraud.OVER_LIMIT }.message)
    check("exactly at the limit counts as reaching it",
        Fraud.OVER_LIMIT in kinds(Fraud.evaluate(
            tx(25000.0, at(17)), emptyList(), highValueLimit = 25000.0)))
    check("a rupee under it does not",
        Fraud.OVER_LIMIT !in kinds(Fraud.evaluate(
            tx(24999.0, at(17)), emptyList(), highValueLimit = 25000.0)))
    check("no limit set means no limit alert",
        Fraud.evaluate(tx(900000.0, at(17)), emptyList()).isEmpty())
    check("zero is not a limit anyone could be over",
        Fraud.evaluate(tx(900000.0, at(17)), emptyList(), highValueLimit = 0.0).isEmpty())

    println("=== abnormally heavy days ===")
    val steady = (1..10).map { tx(1000.0, at(it), "Shop A") }
    val heavy = steady + tx(1500.0, at(17, 9)) + tx(1500.0, at(17, 11))
    val third = tx(1500.0, at(17, 13))
    val heavyDay = Fraud.evaluate(third, heavy)
    check("a day at three times the daily average is flagged",
        Fraud.ABNORMAL_DAY in kinds(heavyDay), kinds(heavyDay).toString())
    check("the day total counts the charge being evaluated",
        heavyDay.first { it.kind == Fraud.ABNORMAL_DAY }.message
            .contains(Insights.money(4500.0)),
        heavyDay.first { it.kind == Fraud.ABNORMAL_DAY }.message)
    // The Python original keys this alert to the transaction, so every
    // further charge on a heavy day repeats the same sentence.
    val fourth = Fraud.evaluate(tx(1500.0, at(17, 15)), heavy + third)
    check("the second charge that day produces the same key, not a second alert",
        heavyDay.first { it.kind == Fraud.ABNORMAL_DAY }.key ==
            fourth.first { it.kind == Fraud.ABNORMAL_DAY }.key,
        fourth.first { it.kind == Fraud.ABNORMAL_DAY }.key)
    check("an ordinary day is not flagged",
        Fraud.ABNORMAL_DAY !in kinds(Fraud.evaluate(tx(1000.0, at(17)), steady)))
    check("the average is taken from earlier days only",
        Fraud.ABNORMAL_DAY !in kinds(Fraud.evaluate(
            tx(1500.0, at(17, 9)), steady)))
    check("too little history means no daily average worth comparing to",
        Fraud.ABNORMAL_DAY !in kinds(Fraud.evaluate(
            tx(9000.0, at(17)), (1..5).map { tx(100.0, at(it), "Shop A") })))

    println("=== payees never seen before ===")
    val first = Fraud.evaluate(tx(2000.0, at(17), "Blue Dart"), quiet)
    check("a large first charge at a new payee is flagged",
        Fraud.NEW_MERCHANT in kinds(first))
    check("quietly — it is the weakest of the five signals",
        first.first { it.kind == Fraud.NEW_MERCHANT }.severity == Fraud.LOW)
    check("a large charge at a payee already in the ledger is not",
        Fraud.NEW_MERCHANT !in kinds(Fraud.evaluate(tx(2000.0, at(17), "Shop A"), quiet)))
    check("a small charge at a new payee is just a new shop",
        Fraud.NEW_MERCHANT !in kinds(Fraud.evaluate(tx(600.0, at(17), "Blue Dart"), quiet)))
    check("a payee known only from a credit is still known",
        Fraud.NEW_MERCHANT !in kinds(Fraud.evaluate(
            tx(2000.0, at(17), "Blue Dart"),
            quiet + tx(90.0, at(3), "Blue Dart", type = "income"))))
    check("with too little history nothing is unusual yet",
        Fraud.evaluate(tx(2000.0, at(17), "Blue Dart"),
            (1..5).map { tx(500.0, at(it), "Shop A") }).isEmpty())

    println("=== keys, ordering and re-evaluation ===")
    val doubled = Fraud.evaluate(tx(40000.0, at(17), "Jeweller"), varied, highValueLimit = 25000.0)
    check("one charge can raise several signals", doubled.size >= 2, kinds(doubled).toString())
    check("the worst is first",
        doubled.first().severity == Fraud.HIGH &&
            doubled.map { Fraud.rank(it.severity) } ==
            doubled.map { Fraud.rank(it.severity) }.sortedDescending())
    check("each signal has its own key", doubled.map { it.key }.distinct().size == doubled.size)
    check("every key names the transaction or the day it is about",
        doubled.all { it.key.endsWith(it.txId) || it.key.endsWith(TODAY.toString()) },
        doubled.map { it.key }.toString())

    val subject = tx(12000.0, at(17), "Shop A")
    check("evaluating the same charge twice produces the same keys",
        Fraud.evaluate(subject, varied).map { it.key } ==
            Fraud.evaluate(subject, varied).map { it.key })
    check("a ledger that already contains the charge gives the same answer",
        Fraud.evaluate(subject, varied).map { it.key } ==
            Fraud.evaluate(subject, varied + subject).map { it.key })

    println("=== an alert nobody can act on is not raised ===")
    val now = TODAY.atTime(12, 0)
    check("a charge from today is worth alerting about",
        Fraud.worthAlerting(at(17), now))
    check("and one from a fortnight ago still is",
        Fraud.worthAlerting(TODAY.minusDays(14).atTime(10, 0), now))
    check("the window is inclusive at its edge",
        Fraud.worthAlerting(TODAY.minusDays(Fraud.ALERT_WINDOW_DAYS).atTime(10, 0), now))
    // The inbox scan backfills ninety days on first launch. Without this the
    // tab opens on a quarter of history the user has already lived through.
    check("a charge the day past the window is not",
        !Fraud.worthAlerting(TODAY.minusDays(Fraud.ALERT_WINDOW_DAYS + 1).atTime(10, 0), now))
    check("nor one from three months back",
        !Fraud.worthAlerting(TODAY.minusDays(90).atTime(10, 0), now))
    check("a charge dated ahead of the clock is never stale",
        Fraud.worthAlerting(TODAY.plusDays(3).atTime(10, 0), now))

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
