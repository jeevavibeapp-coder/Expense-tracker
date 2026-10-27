import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

private val COFFEE = Categories.Row("c-coffee", "Coffee", budgetAmount = 3000.0)
private val COFFEE_GONE = COFFEE.copy(isArchived = true, budgetAmount = null)
private val FOOD = Categories.Row("c-food", "Food & Dining")
private val SALARY = Categories.Row("c-salary", "Salary", type = "income")

private fun tx(id: String, amount: Double, categoryId: String?, day: Int,
               type: String = "expense") =
    Tx(id, amount, type, LocalDateTime.of(2026, 6, day, 10, 0), "Somewhere", categoryId)

fun main() {
    println("=== deleting keeps the row, archived ===")
    val archived = Categories.archive(COFFEE)
    check("a deleted category is archived", archived?.isArchived == true, "got=$archived")
    if (archived != null) {
        check("under the same id, so every transaction filed under it still resolves",
            archived.id == COFFEE.id)
        check("with its name and type untouched",
            archived.name == COFFEE.name && archived.type == COFFEE.type)
        check("and its budget cleared", archived.budgetAmount == null,
            "budget=${archived.budgetAmount}")
        check("a category already archived has nothing to write",
            Categories.archive(archived) == null)
    }
    check("an unbudgeted category stays unbudgeted",
        Categories.archive(FOOD)?.budgetAmount == null)

    println("=== adding a name ===")
    val stored = listOf(COFFEE_GONE, FOOD, SALARY)
    check("a blank name is not a category",
        Categories.claim("   ", stored) == Categories.Claim.Blank)
    check("a free name is created, trimmed",
        Categories.claim("  Rent ", stored) == Categories.Claim.Create("Rent"))
    check("a name a visible category has is taken",
        Categories.claim("Food & Dining", stored) == Categories.Claim.Taken(FOOD.id))
    check("in any case, as the Python build checks it",
        Categories.claim("food & DINING", stored) == Categories.Claim.Taken(FOOD.id))

    val back = Categories.claim("Coffee", stored)
    check("the name of a deleted category brings that category back",
        back is Categories.Claim.Restore && back.row.id == COFFEE.id, "got=$back")
    if (back is Categories.Claim.Restore) {
        check("no longer archived", !back.row.isArchived)
        check("still called what it was, and still an expense",
            back.row.name == "Coffee" && back.row.type == "expense")
    }
    val lower = Categories.claim("coffee", stored)
    check("typed in another case, it still comes back under the name it was filed as",
        lower is Categories.Claim.Restore && lower.row.id == COFFEE.id &&
            lower.row.name == "Coffee", "got=$lower")
    // Python clears the budget when it archives, and so does this app, but a
    // backup is a file anyone can edit. A limit that was invisible the whole
    // time the category was gone is not one the user is choosing to keep.
    val budgetedGhost = Categories.Row("c-old", "Old", budgetAmount = 900.0, isArchived = true)
    val ghostBack = Categories.claim("Old", listOf(budgetedGhost))
    check("a deleted category comes back without a budget, even one its row still carried",
        ghostBack is Categories.Claim.Restore && ghostBack.row.budgetAmount == null,
        "got=$ghostBack")
    check("surrounding spaces do not hide a deleted name",
        Categories.claim(" Coffee  ", stored) is Categories.Claim.Restore)
    check("no stored name, live or deleted, is ever offered as a new insert",
        stored.none {
            Categories.claim(it.name, stored) is Categories.Claim.Create ||
                Categories.claim(it.name.uppercase(), stored) is Categories.Claim.Create
        })

    // Two spellings can already exist side by side: the unique index is
    // case-sensitive, and a restored backup is not held to this rule.
    val liveUpper = Categories.Row("c-travel-2", "TRAVEL")
    val goneTravel = Categories.Row("c-travel-1", "Travel", isArchived = true)
    check("a visible spelling wins over a deleted one, so nothing comes back beside it",
        Categories.claim("Travel", listOf(goneTravel, liveUpper)) ==
            Categories.Claim.Taken(liveUpper.id))
    val goneUpper = liveUpper.copy(isArchived = true)
    val exact = Categories.claim("Travel", listOf(goneUpper, goneTravel))
    check("among deleted spellings, the exact one is the one brought back",
        exact is Categories.Claim.Restore && exact.row.id == goneTravel.id, "got=$exact")

    println("=== renaming ===")
    val rows = listOf(COFFEE_GONE, FOOD, SALARY)
    check("to a free name",
        Categories.rename(FOOD.id, "  Eating out ", rows) == Categories.Rename.To("Eating out"))
    check("to its own name is no change",
        Categories.rename(FOOD.id, "Food & Dining", rows) == Categories.Rename.Unchanged)
    check("a change of case alone is a rename, not a clash with itself",
        Categories.rename(FOOD.id, "Food & dining", rows) == Categories.Rename.To("Food & dining"))
    check("to another visible category's name is refused",
        Categories.rename(FOOD.id, "salary", rows) == Categories.Rename.Taken)
    check("to a deleted category's name says so, rather than 'already exists'",
        Categories.rename(FOOD.id, "COFFEE", rows) == Categories.Rename.HeldByDeleted)
    check("a blank name is refused",
        Categories.rename(FOOD.id, "  ", rows) == Categories.Rename.Blank)
    check("an id nobody has is missing",
        Categories.rename("c-nowhere", "Rent", rows) == Categories.Rename.Missing)
    check("and so is a deleted category: no screen offers it to rename",
        Categories.rename(COFFEE.id, "Cafe", rows) == Categories.Rename.Missing)

    println("=== nothing new is filed under a deleted category ===")
    check("a live category is kept", Categories.fileable(FOOD.id, stored) == FOOD.id)
    check("a deleted one is refused", Categories.fileable(COFFEE.id, stored) == null)
    check("so is an id no row carries", Categories.fileable("c-nowhere", stored) == null)
    check("and no category stays no category", Categories.fileable(null, stored) == null)

    // The rule meeting the pipeline it protects. Pipeline.ingest asks for a
    // category exactly when the resolution carries none, so refusing a deleted
    // one is what turns "filed where nobody looks" into a question.
    val takeaway = Categories.Row("c-takeaway", "Takeaway")
    val body = "Rs.450.00 debited from a/c **1234 on 05-Jan-24 to ZOMATO Ref 883120114455 UPI"
    fun captureWith(rows: List<Categories.Row>) = Pipeline.ingest(
        "AD-HDFCBK", body, LocalDateTime.of(2026, 6, 17, 12, 0), { null },
        { _, _, _ ->
            Pipeline.Resolution("Zomato", Categories.fileable(takeaway.id, rows), 90)
        })
    val whileLive = captureWith(listOf(takeaway, FOOD))
    check("while the category exists, a learned payee is filed under it without asking",
        whileLive is Pipeline.Outcome.Capture &&
            whileLive.categoryId == takeaway.id && !whileLive.needsCategory,
        "got=$whileLive")
    // Falls back to the live row if archive ever stops archiving, so that bug
    // fails the check below instead of crashing the suite.
    val afterDelete = captureWith(listOf(Categories.archive(takeaway) ?: takeaway, FOOD))
    check("once it is deleted, the same payee is asked about instead",
        afterDelete is Pipeline.Outcome.Capture &&
            afterDelete.categoryId == null && afterDelete.needsCategory,
        "got=$afterDelete")
    check("and is still recognised by name",
        (afterDelete as? Pipeline.Outcome.Capture)?.merchantName == "Zomato")
    val restored = Categories.claim("Takeaway", listOf(Categories.archive(takeaway) ?: takeaway))
    val afterRestore = (restored as? Categories.Claim.Restore)?.row
        ?.let { captureWith(listOf(it, FOOD)) }
    check("and once it is added back, the learned payee files under it again",
        afterRestore is Pipeline.Outcome.Capture && afterRestore.categoryId == takeaway.id,
        "got=$afterRestore")

    println("=== pickers ===")
    check("a live category is offered on a new row", Categories.pickable(FOOD, null))
    check("a deleted one is not", !Categories.pickable(COFFEE_GONE, null))
    check("nor on a row filed somewhere else", !Categories.pickable(COFFEE_GONE, FOOD.id))
    check("but it stays on the rows already filed under it",
        Categories.pickable(COFFEE_GONE, COFFEE.id))

    println("=== the confirmation says what will happen ===")
    val ledger = listOf(
        tx("t1", 180.0, COFFEE.id, 3), tx("t2", 220.0, COFFEE.id, 9),
        tx("t3", 900.0, FOOD.id, 10), tx("t4", 50000.0, SALARY.id, 1, type = "income"),
        tx("t5", 75.0, null, 12),
    )
    val notice = Categories.deleteNotice(COFFEE, ledger)
    check("it counts only what is filed under that category", notice.filed == 2, "${notice.filed}")
    check("and says those transactions are kept, still filed under it",
        notice.kept == "Its 2 transactions are kept, still filed under Coffee.", notice.kept)
    check("it says the category is hidden from new entries",
        notice.hidden.startsWith("Coffee is hidden from new entries"), notice.hidden)
    check("it names the budget that goes with it",
        notice.budget == "Its ${Insights.money(3000.0)} monthly budget is removed.",
        "${notice.budget}")
    check("and it says how to undo it", notice.undo == "Add Coffee again to bring it back.")
    check("once done, it repeats the count",
        notice.done == "Deleted Coffee. Its 2 transactions are still filed under it.",
        notice.done)

    val one = Categories.deleteNotice(FOOD, ledger)
    check("one transaction is not '1 transactions'",
        one.kept == "Its 1 transaction is kept, still filed under Food & Dining.", one.kept)
    check("no budget, no sentence about one", one.budget == null, "${one.budget}")
    check("a zero budget is no budget",
        Categories.deleteNotice(FOOD.copy(budgetAmount = 0.0), ledger).budget == null)
    val empty = Categories.deleteNotice(Categories.Row("c-new", "Pets"), ledger)
    check("an empty category says nothing is filed under it",
        empty.filed == 0 && empty.kept == "Nothing is filed under Pets.", empty.kept)
    check("and is simply deleted", empty.done == "Deleted Pets.", empty.done)

    println("=== and the report keeps the promise ===")
    // The confirmation says the transactions stay filed under the category.
    // The report names its lines from whatever categories it is handed, so the
    // screen hands it every category, deleted ones included; these pin both
    // halves of why.
    val afterRows = listOf(COFFEE_GONE, FOOD, SALARY)
    fun reportNaming(named: List<Categories.Row>) =
        Budgets.buildReport(ledger, YearMonth.of(2026, 6), LocalDate.of(2026, 6, 20),
            named.map { Budget(it.id, it.name, it.budgetAmount) })
    val report = reportNaming(afterRows)
    check("a deleted category's spending is still reported under its name",
        report.categories.firstOrNull { it.name == "Coffee" }?.value == 400.0,
        report.categories.toString())
    check("so the category lines still add up to the month's spending",
        report.categories.sumOf { it.value } == report.expense,
        "lines=${report.categories.sumOf { it.value }} expense=${report.expense}")
    val liveOnly = reportNaming(afterRows.filterNot { it.isArchived })
    check("named from live categories only, the same spending would turn Uncategorised",
        liveOnly.categories.none { it.name == "Coffee" } &&
            liveOnly.categories.firstOrNull { it.name == Budgets.UNCATEGORISED }?.value ==
            180.0 + 220.0 + 75.0, liveOnly.categories.toString())

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
