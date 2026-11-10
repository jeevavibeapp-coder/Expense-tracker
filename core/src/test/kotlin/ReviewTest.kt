import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private var seq = 0
private fun item(
    merchant: String?,
    amount: Double = 100.0,
    type: String = "expense",
    day: Int = 1,
    raw: String? = merchant,
    sender: String? = null,
) = Review.Item(
    id = "t${seq++}",
    amount = amount,
    type = type,
    occurredAt = LocalDateTime.of(2026, 6, day, 9, 0),
    rawMerchant = raw,
    merchantName = merchant,
    sender = sender,
)

/** Enough of this user's own history for Categorizer.train to fit a model:
 *  twelve rows over two categories is its documented floor. */
private fun trainedModel(): Categorizer.Model? = Categorizer.train(
    List(8) { "Swiggy order" to "food" } + List(8) { "Uber ride" to "transport" })

fun main() {
    println("=== one decision per payee ===")
    val queue = listOf(
        item("Swiggy", 250.0, day = 1),
        item("Swiggy", 310.0, day = 4),
        item("Swiggy", 120.0, day = 9),
        item("Uber", 180.0, day = 2),
        item("Uber", 220.0, day = 3),
        item("Netflix", 649.0, day = 5),
    )
    val groups = Review.group(queue)
    check("six rows become three questions", groups.size == 3, "got=${groups.size}")
    check("the biggest group is asked first", groups[0].key == "Swiggy", "got=${groups[0].key}")
    check("and carries every row in it", groups[0].count == 3)
    check("with the total the user is deciding about", groups[0].total == 680.0,
        "got=${groups[0].total}")
    check("the span of the group, oldest first",
        groups[0].firstAt == LocalDateTime.of(2026, 6, 1, 9, 0) &&
        groups[0].lastAt == LocalDateTime.of(2026, 6, 9, 9, 0))
    check("and the ids one decision has to write",
        groups[0].ids.size == 3 && groups[0].ids.toSet().size == 3)

    println("=== groups the same tap must not merge ===")
    // A refund and a purchase from one shop are two questions. Answering
    // them together would file money coming in as money going out.
    val mixed = Review.group(listOf(
        item("Amazon", 900.0, type = "expense"),
        item("Amazon", 900.0, type = "income"),
    ))
    check("income and expense from one payee stay apart", mixed.size == 2)
    check("each keeps its own type",
        mixed.map { it.type }.toSet() == setOf("expense", "income"))

    println("=== rows that resolved to no payee ===")
    // The likeliest junk in the queue. Left out of the grouping they would be
    // the one thing bulk review could not clear.
    val nameless = Review.group(listOf(
        item(null, 40.0, raw = null),
        item("", 60.0, raw = ""),
        item(null, 10.0, raw = "  "),
    ))
    check("group together rather than vanish", nameless.size == 1, "got=${nameless.size}")
    check("under a blank name the screen can label", nameless[0].key == "")
    check("carrying all three", nameless[0].count == 3)
    check("and offering no raw string to learn from", nameless[0].rawSample == null)

    println("=== the raw payee string survives grouping ===")
    val raws = Review.group(listOf(
        item("Swiggy", raw = null),
        item("Swiggy", raw = "SWIGGY8@YBL"),
        item("Swiggy", raw = "SWIGGY LTD", sender = "AD-HDFCBK"),
    ))
    check("a resolved name folds every spelling into one group", raws.size == 1)
    check("and the raw string is still reachable", raws[0].rawSample == "SWIGGY8@YBL")
    check("as is the sender that sent them", raws[0].sender == "AD-HDFCBK")
    check("a row filed under its raw string keys on that",
        Review.Item("x", 1.0, "expense", LocalDateTime.now(), rawMerchant = "PAYTM").key == "PAYTM")

    println("=== ordering: the tap that clears the most comes first ===")
    val ordered = Review.group(
        List(2) { item("Small", 5000.0) } +
        List(2) { item("Rich", 9000.0) } +
        List(4) { item("Many", 10.0) })
    check("count wins over money", ordered[0].key == "Many", "got=${ordered[0].key}")
    check("money breaks a tie on count", ordered[1].key == "Rich", "got=${ordered[1].key}")
    check("and the order is stable across re-grouping",
        Review.group(ordered.flatMap { it.items }).map { it.key } == ordered.map { it.key })

    println("=== suggestions pre-fill, they never decide ===")
    val model = trainedModel()
    check("the model fits from this user's own rows", model != null)
    val known = setOf("food", "transport")
    val suggested = Review.suggest(Review.group(listOf(
        item("Swiggy", raw = "SWIGGY ORDER"),
        item("Uber", raw = "UBER RIDE"),
        item("Kirana Store", raw = "SRI LAKSHMI KIRANA"),
    )), model, known)
    val swiggy = suggested.first { it.key == "Swiggy" }
    check("a payee the history knows is pre-filled",
        swiggy.suggestedCategoryId == "food", "got=${swiggy.suggestedCategoryId}")
    check("with a confidence a person can weigh",
        swiggy.suggestedConfidence in 62..100, "got=${swiggy.suggestedConfidence}")
    check("and the words behind it", swiggy.because.isNotEmpty(),
        "because=${swiggy.because}")
    check("a payee it has never seen is left blank",
        suggested.first { it.key == "Kirana Store" }.suggestedCategoryId == null)
    check("nothing is applied — the rows keep their own categories",
        suggested.all { g -> g.items.size == 1 })

    println("=== a suggestion the picker cannot show is no suggestion ===")
    // A model trained before a category was archived still names it. A chip
    // for a category that is gone does nothing when tapped.
    val stale = Review.suggest(Review.group(listOf(item("Swiggy", raw = "SWIGGY ORDER"))),
        model, setOf("transport"))
    check("an unknown category id is dropped", stale[0].suggestedCategoryId == null,
        "got=${stale[0].suggestedCategoryId}")
    val untrained = Review.suggest(Review.group(listOf(item("Swiggy"))), null, known)
    check("and with too little history to fit a model, nothing is suggested",
        untrained[0].suggestedCategoryId == null && untrained[0].because.isEmpty())

    println("=== confirming a group teaches the engine ===")
    val big = Review.group(List(40) { item("Swiggy", raw = "SWIGGY8@YBL") })[0]
    check("a bulk confirmation is many pieces of evidence, not one",
        Review.teachFrom(big).size == Review.LEARN_LIMIT, "got=${Review.teachFrom(big).size}")
    check("a small group teaches from all of it",
        Review.teachFrom(Review.group(List(3) { item("Uber", raw = "UBER") })[0]).size == 3)
    check("rows with no raw name have nothing to teach",
        Review.teachFrom(Review.group(List(3) { item("Uber", raw = null) })[0]).isEmpty())
    check("and are not what the cap counts",
        Review.teachFrom(Review.group(
            List(3) { item("Uber", raw = "UBER") } + List(3) { item("Uber", raw = null) })[0])
            .size == 3)

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
