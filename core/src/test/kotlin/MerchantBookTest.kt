import com.jeevavibeapp.spendwise.core.*
import java.time.LocalDate
import java.time.YearMonth

private var passed = 0
private var failed = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) passed++ else { failed++; println("  FAIL  $name${if (detail.isNotEmpty()) "  [$detail]" else ""}") }
}

/** Pinned, so the six-month window cannot drift with the day the suite runs. */
private val TODAY = LocalDate.of(2026, 6, 17)
private var seq = 0

private fun tx(amount: Double, date: LocalDate, merchant: String? = "Swiggy",
               type: String = "expense", cat: String? = "food", hour: Int = 13,
               id: String = "t${seq++}") =
    Tx(id, amount, type, date.atTime(hour, 0), merchant, cat)

private fun learned(raw: String, name: String, cat: String? = "food", confirmations: Int = 3,
                    corrections: Int = 0, samples: Int = 3, min: Double = 200.0,
                    max: Double = 600.0) =
    LearningRow(raw, name, cat, confirmations, corrections, samples,
        (min + max) / 2, min, max, List(24) { if (it == 13) samples else 0 })

fun main() {
    println("=== the ledger half: totals ===")
    val ledger = listOf(
        tx(300.0, LocalDate.of(2026, 6, 10), id = "a"),
        tx(500.0, LocalDate.of(2026, 6, 2), id = "b"),
        tx(200.0, LocalDate.of(2026, 4, 20), id = "c"),
        tx(150.0, LocalDate.of(2026, 5, 1), type = "income", cat = null, id = "d"),
        tx(999.0, LocalDate.of(2026, 6, 11), merchant = "Zomato", id = "e"),
        tx(888.0, LocalDate.of(2026, 6, 12), merchant = "SWIGGY", id = "f"),
        tx(10000.0, LocalDate.of(2025, 1, 5), id = "g"),
    )
    val s = MerchantBook.summary(ledger, "Swiggy", TODAY)
    check("spent counts this merchant's expenses only", s.spent == 11000.0, "spent=${s.spent}")
    check("money back is counted apart, not netted off", s.received == 150.0, "received=${s.received}")
    check("payments are expenses, as the report counts visits", s.payments == 4, "payments=${s.payments}")
    check("every row listed under the name is on the page", s.count == 5, "count=${s.count}")
    check("the average is per payment", s.avgPayment == 2750.0, "avg=${s.avgPayment}")
    check("the name is matched exactly, as the report groups it",
        "f" !in s.ids && "e" !in s.ids)
    check("newest first", s.ids == listOf("a", "b", "d", "c", "g"), "ids=${s.ids}")
    check("first and last are dates of the oldest and newest",
        s.first == LocalDate.of(2025, 1, 5) && s.last == LocalDate.of(2026, 6, 10),
        "first=${s.first} last=${s.last}")
    val tied = MerchantBook.summary(listOf(
        tx(1.0, TODAY, id = "z"), tx(1.0, TODAY, id = "m")), "Swiggy", TODAY)
    check("two payments at the same minute keep a stable order", tied.ids == listOf("m", "z"))

    println("=== the ledger half: month by month ===")
    check("six months, ending with this one",
        s.months.size == MerchantBook.TREND_MONTHS && s.months.last().month == YearMonth.of(2026, 6),
        "months=${s.months.map { it.month }}")
    check("consecutive and oldest first",
        s.months.zipWithNext().all { (x, y) -> x.month.plusMonths(1) == y.month })
    val byMonth = s.months.associate { it.month to it.amount }
    check("a month's spend is summed", byMonth[YearMonth.of(2026, 6)] == 800.0,
        "june=${byMonth[YearMonth.of(2026, 6)]}")
    check("an empty month is kept as zero, not dropped", byMonth[YearMonth.of(2026, 5)] == 0.0,
        "may=${byMonth[YearMonth.of(2026, 5)]}")
    check("money back is not drawn as spending", byMonth[YearMonth.of(2026, 5)] == 0.0)
    check("spend before the window is in the total but not the chart",
        s.months.sumOf { it.amount } == 1000.0 && s.spent == 11000.0)
    check("two months with spend is a trend", s.hasTrend)
    check("one month among zeros is not",
        !MerchantBook.summary(listOf(tx(400.0, TODAY)), "Swiggy", TODAY).hasTrend)
    val ahead = MerchantBook.summary(listOf(tx(400.0, TODAY), tx(700.0, TODAY.plusMonths(2))),
        "Swiggy", TODAY)
    check("a post-dated payment counts in the total, outside the window",
        ahead.spent == 1100.0 && ahead.months.sumOf { it.amount } == 400.0)

    println("=== the ledger half: nothing there ===")
    val none = MerchantBook.summary(ledger, "Nobody", TODAY)
    check("an unknown name is an empty page, not a failure",
        none.count == 0 && none.spent == 0.0 && none.avgPayment == 0.0 &&
        none.first == null && none.last == null && !none.hasTrend)
    check("still six zero months", none.months.size == 6 && none.months.all { it.amount == 0.0 })
    check("a blank name matches nothing, even rows with a blank name",
        MerchantBook.summary(listOf(tx(5.0, TODAY, merchant = "")), "", TODAY).count == 0)

    println("=== which side of the ledger ===")
    check("a shop is an expense", s.side == "expense")
    val employer = MerchantBook.summary((1..3).map {
        tx(50000.0, LocalDate.of(2026, it, 1), merchant = "Acme", type = "income")
    } + tx(200.0, TODAY, merchant = "Acme"), "Acme", TODAY)
    check("an employer is income", employer.side == "income")
    check("a tie stays an expense", MerchantBook.summary(listOf(
        tx(1.0, TODAY, type = "income"), tx(1.0, TODAY)), "Swiggy", TODAY).side == "expense")

    println("=== the keys the next payment is looked up under ===")
    val keys = MerchantBook.rawNames(listOf("SWIGGY8@ybl", "Swiggy", "swiggy", null, "", "UPI/123456"))
    check("every spelling of one payee folds to one key", keys == setOf("SWIGGY"), "keys=$keys")
    check("a raw name that folds to nothing is not a key", "" !in keys)
    val many = MerchantBook.rawNames((1..400).map { "Shop ${'A' + it % 26}${'A' + it / 26}" })
    check("the lookup is capped", many.size == MerchantBook.MAX_RAW_NAMES, "size=${many.size}")
    check("and the cap keeps a stable, sorted set", many.toList() == many.sorted())

    println("=== what decides the next payment ===")
    val rows = listOf(
        learned("SWIGGY", "Swiggy", confirmations = 12, corrections = 1),
        learned("SWIGGY FOOD", "Swiggy", confirmations = 2),
        learned("PAYTM", "Uber", cat = "transport"),       // one of OUR raw names, now naming Uber
        learned("OLA", "Ola", cat = "transport"),          // unrelated, should be ignored
    )
    val raw = setOf("SWIGGY", "PAYTM", "ZOMATO", "CORNER BAKERY")
    val f = MerchantBook.filing(rows, "Swiggy", raw)
    check("rows naming the merchant are its own", f.own.map { it.rawName } == listOf("SWIGGY", "SWIGGY FOOD"),
        "own=${f.own.map { it.rawName }}")
    check("strongest first", f.own.first().confirmations == 12)
    check("one of its raw names now naming someone else is surfaced",
        f.elsewhere.map { it.rawName } == listOf("PAYTM") && f.elsewhere[0].merchantName == "Uber")
    check("a row with nothing to do with this merchant is ignored",
        (f.own + f.elsewhere).none { it.rawName == "OLA" })
    check("a bare raw name the built-in table knows is filed by it",
        f.builtIn.size == 1 && f.builtIn[0].rawName == "ZOMATO" &&
        f.builtIn[0].display == "Zomato" && f.builtIn[0].categoryName == "Food & Dining",
        "builtIn=${f.builtIn}")
    check("a bare raw name nobody knows is a guess each time", f.unlearned == listOf("CORNER BAKERY"))
    check("a learned raw name is never also listed as bare",
        "SWIGGY" !in f.unlearned && f.builtIn.none { it.rawName == "SWIGGY" || it.rawName == "PAYTM" })
    check("every learned row agreeing is one category", f.categories == setOf<String?>("food"))
    check("the page can confidently say how new payments are filed", f.canRelearn)
    check("confidence is the engine's own baseline",
        f.own[0].confidence == Engine.baselineConfidence(rows[0]))
    check("the usual range is the amounts seen", f.own[0].usualMin == 200.0 && f.own[0].usualMax == 600.0)
    val unseen = MerchantBook.filing(listOf(learned("CAFE", "Cafe", samples = 0, min = 0.0, max = 0.0)),
        "Cafe", setOf("CAFE"))
    check("no amounts seen is no range, not ₹0–₹0",
        unseen.own[0].usualMin == null && unseen.own[0].usualMax == null)
    val split = MerchantBook.filing(listOf(learned("A", "Shop", cat = "x"), learned("B", "Shop", cat = null)),
        "Shop", setOf("A", "B"))
    check("disagreeing rows are reported as more than one category",
        split.categories == setOf<String?>("x", null))
    val empty = MerchantBook.filing(emptyList(), "Ghost", emptySet())
    check("a name with no raw names and no learning has nothing to change", !empty.canRelearn)

    println("=== a deleted category files nothing new ===")
    val cats = listOf(
        Categories.Row("food", "Food"),
        Categories.Row("transport", "Transport", isArchived = true),
        Categories.Row("dining", "Food & Dining", isArchived = true),
    )
    val r = MerchantBook.refused(f, cats)
    check("a live category is not refused", "food" !in r.learned, "learned=${r.learned}")
    check("a deleted one another merchant's row points at is", r.learned == setOf("transport"),
        "learned=${r.learned}")
    check("a built-in category is refused by name when deleted",
        r.builtIn == setOf("Food & Dining"), "builtIn=${r.builtIn}")
    check("so the page has something to say", !r.isEmpty())
    val live = MerchantBook.refused(f, cats.map { it.copy(isArchived = false) })
    check("with every category back, nothing is refused", live.isEmpty(), "refused=$live")
    check("refused exactly when the resolver refuses",
        (f.own + f.elsewhere).mapNotNull { it.categoryId }.all { id ->
            (id in r.learned) == (Categories.fileable(id, cats) == null)
        })
    check("a renamed built-in category is refused too: the resolver finds it by name",
        MerchantBook.refused(f, listOf(Categories.Row("food", "Food"),
            Categories.Row("dining", "Eating out"), Categories.Row("transport", "Transport")))
            .builtIn == setOf("Food & Dining"))
    check("an id no category holds is refused, as the resolver refuses it",
        MerchantBook.refused(f, listOf(Categories.Row("dining", "Food & Dining")))
            .learned == setOf("food", "transport"))
    check("a learned name with no category is not refused",
        MerchantBook.refused(split, emptyList()).learned == setOf("x"))

    println("=== changing the learned category ===")
    val mature = learned("SWIGGY", "Swiggy", cat = "groceries", confirmations = 12, samples = 12)
    val moved = MerchantBook.recategorise(mature, "food")
    check("the category changes", moved?.categoryId == "food")
    check("moving a filed mapping is a correction, as Repo.save counts it",
        moved?.correctionCount == 1 && moved.confirmationCount == 12)
    check("what it learned about the merchant is kept",
        moved?.merchantName == "Swiggy" && moved.sampleCount == 12 &&
        moved.amountMin == mature.amountMin && moved.hourHistogram == mature.hourHistogram)
    check("re-choosing the current category writes nothing",
        MerchantBook.recategorise(mature, "groceries") == null)
    val bare = learned("CAFE", "Cafe", cat = null, confirmations = 1)
    val first = MerchantBook.recategorise(bare, "food")
    check("a first category on an unfiled mapping is a confirmation",
        first?.confirmationCount == 2 && first.correctionCount == 0)
    val before = Engine.baselineConfidence(mature)
    val after = Engine.baselineConfidence(moved!!)
    check("one correction does not turn a mature mapping into a question",
        after >= before - 2, "before=$before after=$after")

    println("=== the plan for 'file new payments under…' ===")
    val plan = MerchantBook.relearn(rows, "Swiggy", raw, "groceries")
    check("its own rows are moved", plan.changed.map { it.rawName }.toSet() == setOf("SWIGGY", "SWIGGY FOOD")
        && plan.changed.all { it.categoryId == "groceries" })
    check("a row naming another merchant is never taken over",
        plan.changed.none { it.rawName == "PAYTM" } && plan.taught.none { it.rawName == "PAYTM" })
    check("bare spellings are taught, so the decision holds for every one",
        plan.taught.map { it.rawName } == listOf("CORNER BAKERY", "ZOMATO"))
    check("taught under this merchant's name and the chosen category",
        plan.taught.all { it.merchantName == "Swiggy" && it.categoryId == "groceries" })
    val same = MerchantBook.relearn(listOf(learned("SWIGGY", "Swiggy", cat = "food")), "Swiggy",
        setOf("SWIGGY"), "food")
    check("choosing what is already learned is an empty plan", same.isEmpty)

    println("=== a taught row claims no evidence it does not have ===")
    val t = MerchantBook.taught("CORNER BAKERY", "Corner Bakery", "food")
    check("one confirmation, no samples, no corrections",
        t.confirmationCount == 1 && t.sampleCount == 0 && t.correctionCount == 0)
    check("a full day of empty hours", t.hourHistogram.size == 24 && t.hourHistogram.sum() == 0)
    val next = Engine.score(t, 250.0, t.categoryId, TODAY.atTime(13, 0))
    check("so the next payment is asked about, not auto-filed",
        next.total < 80, "score=${next.total}")

    println("=== moving past payments is an offer, grouped ===")
    val history = listOf(
        tx(100.0, TODAY, cat = "groceries", id = "g1"),
        tx(100.0, TODAY, cat = "groceries", id = "g2"),
        tx(100.0, TODAY, cat = "groceries", id = "g3"),
        tx(100.0, TODAY, cat = null, id = "n1"),
        tx(100.0, TODAY, cat = "gifts", id = "x1"),
        tx(100.0, TODAY, cat = "food", id = "f1"),
        tx(100.0, TODAY, cat = null, type = "income", id = "r1"),
        tx(100.0, TODAY, merchant = "Zomato", cat = "groceries", id = "z1"),
    )
    val groups = MerchantBook.refileGroups(history, "Swiggy", "food", "expense")
    check("one group per category the payments sit in now",
        groups.map { it.from } == listOf("groceries", "gifts", null), "from=${groups.map { it.from }}")
    check("largest first, with every id", groups[0].ids == listOf("g1", "g2", "g3"))
    check("payments already in the target are not offered", groups.none { "f1" in it.ids })
    check("another merchant's payments are not offered", groups.none { "z1" in it.ids })
    check("a refund is not offered for an expense category", groups.none { "r1" in it.ids })
    check("uncategorised is a group of its own", groups.any { it.from == null && it.ids == listOf("n1") })
    check("nothing to move is no offer",
        MerchantBook.refileGroups(listOf(tx(1.0, TODAY, cat = "food")), "Swiggy", "food", "expense").isEmpty())

    println()
    println("=".repeat(60))
    println("$passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
