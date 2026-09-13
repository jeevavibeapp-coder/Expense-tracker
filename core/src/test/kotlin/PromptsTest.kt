import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDateTime

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private var seq = 0
private fun row(
    merchant: String? = "Swiggy",
    raw: String? = merchant,
    amount: Double = 250.0,
    type: String = "expense",
    day: Int = 1,
    source: String = "sms",
    categoryId: String? = null,
    prompted: Boolean = false,
    deleted: Boolean = false,
    id: String = "t${seq++}",
) = CategoryPrompt.Row(
    id = id,
    amount = amount,
    type = type,
    occurredAt = LocalDateTime.of(2026, 6, day, 9, 0),
    source = source,
    categoryId = categoryId,
    categoryPrompted = prompted,
    isDeleted = deleted,
    rawMerchant = raw,
    merchantName = merchant,
)

private val CATEGORIES = listOf(
    CategoryPrompt.Option("food", "Food & Dining", "expense"),
    CategoryPrompt.Option("transport", "Transport", "expense"),
    CategoryPrompt.Option("salary", "Salary", "income"),
)

fun main() {
    println("=== only captures the app filed by itself are asked about ===")
    val queue = CategoryPrompt.pending(listOf(
        row(day = 3),
        row("Netflix", day = 5),
        row("Rent", source = "manual"),
        row("Uber", categoryId = "transport"),
        row("Zomato", prompted = true),
        row("Amazon", deleted = true),
    ))
    check("a capture with no category is a question", queue.size == 2, "got=${queue.size}")
    check("newest first, because it is the one being remembered",
        queue[0].merchantName == "Netflix", "got=${queue[0].merchantName}")
    check("a manual row was entered on a screen with a picker on it",
        queue.none { it.merchantName == "Rent" })
    check("a categorised capture has nothing to ask",
        queue.none { it.merchantName == "Uber" })
    check("and one already asked about is not asked twice",
        queue.none { it.merchantName == "Zomato" })
    check("a deleted row is not a question", queue.none { it.merchantName == "Amazon" })

    println("=== the queue is a nudge, not a backlog ===")
    val flood = CategoryPrompt.pending(List(60) { row(day = 1 + it % 28) })
    check("a year of capture still asks one screenful",
        flood.size == CategoryPrompt.MAX_PENDING, "got=${flood.size}")
    // Two captures from the same minute must not swap places between
    // recompositions: the question would move out from under the finger.
    val tied = List(6) { row(day = 4, id = "id$it") }
    check("captures from the same moment keep a stable order",
        CategoryPrompt.pending(tied).map { it.id } ==
            CategoryPrompt.pending(tied.reversed()).map { it.id })
    check("nothing captured, nothing asked", CategoryPrompt.pending(emptyList()).isEmpty())

    println("=== answering files the row and teaches the engine ===")
    val assignable = setOf("food", "transport")
    val picked = CategoryPrompt.answer(
        row(merchant = "Swiggy", raw = "SWIGGY8@YBL"), "food", assignable)
    check("a chosen category is applied", picked is CategoryPrompt.Answer.Assign &&
        picked.categoryId == "food")
    check("the engine learns from the string the NEXT message will arrive with",
        (picked as CategoryPrompt.Answer.Assign).learnFrom == "SWIGGY8@YBL",
        "got=${picked.learnFrom}")
    check("under the name the user sees", picked.merchantName == "Swiggy",
        "got=${picked.merchantName}")

    val unresolved = CategoryPrompt.answer(
        row(merchant = null, raw = "  PAYTM*4471 "), "food", assignable)
    check("an unresolved payee is filed under the raw string",
        (unresolved as CategoryPrompt.Answer.Assign).merchantName == "PAYTM*4471",
        "got=${unresolved.merchantName}")
    check("and teaches from it too", unresolved.learnFrom == "PAYTM*4471")

    // A row the message carried no payee for has nothing an incoming message
    // could ever match, so there is no learning row to write.
    val nameless = CategoryPrompt.answer(row(merchant = "Cash", raw = null), "food", assignable)
    check("a row with no raw payee is still filed",
        (nameless as CategoryPrompt.Answer.Assign).categoryId == "food")
    check("but teaches nothing", nameless.learnFrom == null)
    check("and leaves the stored name alone", nameless.merchantName == null)

    println("=== declining is an answer ===")
    check("skipping is not a category",
        CategoryPrompt.answer(row(), null, assignable) == CategoryPrompt.Answer.Skip)
    // Filing income under a spending category would put money coming in
    // into a budget bar as if it had been spent.
    check("a category of the wrong direction is refused",
        CategoryPrompt.answer(row(type = "income"), "salary", assignable) ==
            CategoryPrompt.Answer.Skip)
    check("and a category that no longer exists is too",
        CategoryPrompt.answer(row(), "archived-months-ago", assignable) ==
            CategoryPrompt.Answer.Skip)

    println("=== a category typed into the prompt ===")
    check("nothing typed is nothing to do",
        CategoryPrompt.typedCategory("   ", CATEGORIES, "expense") == CategoryPrompt.Typed.Blank &&
        CategoryPrompt.typedCategory(null, CATEGORIES, "expense") == CategoryPrompt.Typed.Blank)
    check("an existing name is that category, whatever the case",
        CategoryPrompt.typedCategory(" food & DINING ", CATEGORIES, "expense") ==
            CategoryPrompt.Typed.Existing("food"))
    check("a new name is created for the row's own direction",
        CategoryPrompt.typedCategory("Pharmacy", CATEGORIES, "income") ==
            CategoryPrompt.Typed.Create("Pharmacy", "income"))
    // The Python build hard-codes "expense" here and looks existing names up
    // across both directions, so typing "Salary" on a purchase files it as
    // income. Names are unique, so there is no second Salary to create.
    check("a name taken by the other direction is refused, not misfiled",
        CategoryPrompt.typedCategory("salary", CATEGORIES, "expense") ==
            CategoryPrompt.Typed.WrongType("Salary", "income"))
    val long = CategoryPrompt.typedCategory("x".repeat(200), CATEGORIES, "expense")
    check("a pasted message cannot become a category name",
        (long as CategoryPrompt.Typed.Create).name.length == CategoryPrompt.NAME_LIMIT,
        "got=${long.name.length}")

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
