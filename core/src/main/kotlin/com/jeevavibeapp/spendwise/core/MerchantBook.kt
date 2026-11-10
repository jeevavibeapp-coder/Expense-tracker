package com.jeevavibeapp.spendwise.core

import java.time.LocalDate
import java.time.YearMonth

/**
 * One merchant, drilled into.
 *
 * Two halves. The ledger half — what has been spent there and how it moves
 * month to month — is arithmetic over the same [Tx] list the report reads,
 * matched on the same name, so the page a row in "Merchants this month" opens
 * describes exactly the payments that row summed.
 *
 * The learning half is why the page exists. The learning table is what names
 * and files the NEXT message from a payee — `Repo.resolveMerchant` reads it
 * before the built-in table and the categoriser — so one wrong row misfiles
 * every capture under that raw name, silently, for as long as nobody can see
 * it. This object decides what changing or forgetting such a row does, and
 * above all what it does not do: neither one rewrites a transaction already in
 * the ledger. Moving past payments is a separate offer ([refileGroups]) that
 * nothing here applies on the user's behalf.
 */
object MerchantBook {

    /** The six months the report's cash flow and category trends use, so the
     *  merchant's chart and the report never disagree about "recently". */
    const val TREND_MONTHS = 6

    /** Raw spellings looked up per merchant. A name paid under this many
     *  distinct spellings is a collision rather than a merchant, and the
     *  lookup has to stay well inside SQLite's bound-variable limit. */
    const val MAX_RAW_NAMES = 200

    data class MonthSpend(val month: YearMonth, val amount: Double)

    data class Summary(
        val name: String,
        /** Every transaction listed under [name], newest first. */
        val ids: List<String>,
        /** Expenses only — what the report calls visits. */
        val payments: Int,
        val spent: Double,
        /** Money from the same name: refunds, cashback, a friend paying back. */
        val received: Double,
        val avgPayment: Double,
        val first: LocalDate?,
        val last: LocalDate?,
        /** [TREND_MONTHS] calendar months ending with today's, oldest first,
         *  empty ones kept. Dropping a gap would draw January and June side by
         *  side as if they were consecutive. */
        val months: List<MonthSpend>,
        /** Which side of the ledger this name mostly sits on, and so which
         *  categories it can sensibly be filed under. */
        val side: String,
    ) {
        val count: Int get() = ids.size

        /** One month with spend is a single bar among zeros, which says no
         *  more than the date of the last payment beside it. */
        val hasTrend: Boolean get() = months.count { it.amount > 0 } >= 2
    }

    /**
     * Totals and the monthly series for [name].
     *
     * Matched exactly, as `Insights.merchantInsights` groups: "Swiggy" and
     * "SWIGGY" are two rows in the report, and a page that merged them would
     * show a total no report row ever showed.
     */
    fun summary(txs: List<Tx>, name: String, today: LocalDate,
                months: Int = TREND_MONTHS): Summary {
        val mine = if (name.isBlank()) emptyList() else txs
            .filter { it.merchantName == name }
            .sortedWith(compareByDescending<Tx> { it.occurredAt }.thenBy { it.id })
        val expenses = mine.filter { it.type == "expense" }
        val incomeRows = mine.count { it.type == "income" }
        val spent = expenses.sumOf { it.amount }

        val thisMonth = YearMonth.from(today)
        val span = months.coerceAtLeast(1)
        val byMonth = expenses.groupBy { YearMonth.from(it.occurredAt) }
        val series = (span - 1 downTo 0).map { back ->
            val m = thisMonth.minusMonths(back.toLong())
            MonthSpend(m, Analytics.round2(byMonth[m].orEmpty().sumOf { it.amount }))
        }

        return Summary(
            name = name,
            ids = mine.map { it.id },
            payments = expenses.size,
            spent = Analytics.round2(spent),
            received = Analytics.round2(mine.filter { it.type == "income" }.sumOf { it.amount }),
            avgPayment = if (expenses.isEmpty()) 0.0 else Analytics.round2(spent / expenses.size),
            first = mine.lastOrNull()?.occurredAt?.toLocalDate(),
            last = mine.firstOrNull()?.occurredAt?.toLocalDate(),
            months = series,
            // An employer is filed under Salary, not Groceries. Ties go to
            // expense, the side nearly every merchant lives on.
            side = if (incomeRows > expenses.size) "income" else "expense",
        )
    }

    /**
     * The keys the next payment from this merchant is looked up under.
     *
     * Each raw payee its payments arrived with, folded exactly as the
     * resolver folds before reading the learning table — two spellings that
     * fold together are one key because they are one row. Pass each row's
     * `rawMerchant ?: merchantName`, which is the string `Repo.save` teaches
     * under.
     */
    fun rawNames(raws: List<String?>): Set<String> =
        raws.asSequence().distinct()
            .map { Engine.normalizeMerchant(it) }
            .filter { it.isNotEmpty() }
            .distinct().sorted().take(MAX_RAW_NAMES)
            .toCollection(LinkedHashSet())

    /** A learned row, as the page shows it. */
    data class Mapping(
        /** The folded raw name a message is matched on. */
        val rawName: String,
        /** What a message under [rawName] is named. */
        val merchantName: String,
        val categoryId: String?,
        val confirmations: Int,
        val corrections: Int,
        /** The amounts seen, or null before any were: a range of "₹0–₹0"
         *  would read as a promise that the payee charges nothing. */
        val usualMin: Double?,
        val usualMax: Double?,
        /** How sure the mapping is with no particular message to compare. */
        val confidence: Int,
    )

    /** A raw name nothing was learned for that the built-in table knows. */
    data class BuiltIn(val rawName: String, val display: String, val categoryName: String)

    /**
     * Everything that decides how this merchant's next payment is named and
     * filed.
     *
     * @property own learned rows that name this merchant.
     * @property elsewhere learned rows keyed on a raw name this merchant was
     *   paid under that now name a DIFFERENT merchant — the misfiling seen
     *   from the side it is taking payments away from. Only forgetting is
     *   offered for these: changing one from here would move that other
     *   merchant's future payments without its page ever saying so.
     * @property builtIn raw names with no learning that the built-in table
     *   recognises, so they are filed by it.
     * @property unlearned raw names with no learning and no built-in entry:
     *   every new payment under one is a fresh guess.
     */
    data class Filing(
        val own: List<Mapping>,
        val elsewhere: List<Mapping>,
        val builtIn: List<BuiltIn>,
        val unlearned: List<String>,
    ) {
        /** One entry when every learned row agrees, which is the usual case. */
        val categories: Set<String?> get() = own.mapTo(LinkedHashSet()) { it.categoryId }

        /** Whether "file new payments under…" has anything to write. */
        val canRelearn: Boolean get() =
            own.isNotEmpty() || builtIn.isNotEmpty() || unlearned.isNotEmpty()
    }

    /**
     * @param rows learned rows naming [name] or keyed on one of [rawNames];
     *   anything else passed in is ignored rather than trusted.
     */
    fun filing(rows: List<LearningRow>, name: String, rawNames: Set<String>): Filing {
        val learned = rows.mapTo(HashSet()) { it.rawName }
        val bare = rawNames.filter { it.isNotEmpty() && it !in learned }.sorted()
        return Filing(
            own = rows.filter { it.merchantName == name }
                .sortedWith(compareByDescending<LearningRow> { it.confirmationCount }
                    .thenBy { it.rawName })
                .map(::mapping),
            elsewhere = rows.filter { it.merchantName != name && it.rawName in rawNames }
                .sortedBy { it.rawName }
                .map(::mapping),
            builtIn = bare.mapNotNull { raw ->
                Engine.seedLookup(raw)?.let { (display, category) -> BuiltIn(raw, display, category) }
            },
            unlearned = bare.filter { Engine.seedLookup(it) == null },
        )
    }

    /**
     * The categories [f] names that a new payment can no longer be filed
     * under.
     *
     * A learned row outlives the category it points at: delete the category
     * and the row is kept, so adding it back brings its payees back too. While
     * it is gone the row files nothing — `Repo.resolveMerchant` passes every
     * source through [Categories.fileable], and the payment asks for a
     * category instead. A page that kept saying "filed under Entertainment"
     * would describe something that stopped happening when the user deleted
     * it. The built-in table is looked up by category NAME, as the resolver
     * looks it up, so a seeded category the user renamed or deleted is
     * refused here exactly when it is refused there.
     *
     * @property learned category ids of [Filing.own] and [Filing.elsewhere]
     *   rows; null is never in it, because "no category" is not refused.
     * @property builtIn [BuiltIn.categoryName]s with no category to file under.
     */
    data class Refused(val learned: Set<String>, val builtIn: Set<String>) {
        fun isEmpty(): Boolean = learned.isEmpty() && builtIn.isEmpty()
    }

    /** @param rows every category, deleted ones included. */
    fun refused(f: Filing, rows: List<Categories.Row>): Refused = Refused(
        learned = (f.own + f.elsewhere).mapNotNull { it.categoryId }
            .filterTo(LinkedHashSet()) { Categories.fileable(it, rows) == null },
        builtIn = f.builtIn.map { it.categoryName }
            .filterTo(LinkedHashSet()) { name ->
                Categories.fileable(rows.firstOrNull { it.name == name }?.id, rows) == null
            },
    )

    private fun mapping(row: LearningRow) = Mapping(
        rawName = row.rawName,
        merchantName = row.merchantName,
        categoryId = row.categoryId,
        confirmations = row.confirmationCount,
        corrections = row.correctionCount,
        usualMin = row.amountMin.takeIf { row.sampleCount > 0 },
        usualMax = row.amountMax.takeIf { row.sampleCount > 0 },
        confidence = Engine.baselineConfidence(row),
    )

    /**
     * A learned row refiled under [to] by hand.
     *
     * Counted the way `Repo.save` counts the same decision made on a single
     * transaction, so the two routes to it teach the engine identically:
     * moving a categorised mapping is a correction, giving an uncategorised
     * one its first category is a confirmation. The name, amounts and hours
     * are kept — the mapping recognised the merchant correctly; its category
     * is the part that was wrong.
     *
     * Null when [to] is already the category, so tapping the current choice
     * cannot quietly erode the mapping's confidence.
     */
    fun recategorise(row: LearningRow, to: String): LearningRow? = when (row.categoryId) {
        to -> null
        null -> row.copy(categoryId = to, confirmationCount = row.confirmationCount + 1)
        else -> row.copy(categoryId = to, correctionCount = row.correctionCount + 1)
    }

    /** The learning writes for "file new payments from this merchant under…".
     *  Ledger rows are deliberately absent from it. */
    data class Relearn(val changed: List<LearningRow>, val taught: List<LearningRow>) {
        val isEmpty: Boolean get() = changed.isEmpty() && taught.isEmpty()
    }

    /**
     * Everything "file new payments from [name] under [to]" writes.
     *
     * Learned rows naming the merchant are recategorised. Raw names it has
     * been paid under that nothing was learned for are taught [to] too —
     * otherwise a spelling the built-in table recognises keeps arriving under
     * the old category, and the decision holds for some of the merchant's
     * payments and not others.
     *
     * Rows keyed on its raw names that name a different merchant are left
     * exactly as they are; see [Filing.elsewhere].
     *
     * @param rows learned rows naming [name] or keyed on one of [rawNames],
     *   read fresh at the moment of writing rather than from the screen.
     */
    fun relearn(rows: List<LearningRow>, name: String, rawNames: Set<String>,
                to: String): Relearn {
        val learned = rows.mapTo(HashSet()) { it.rawName }
        return Relearn(
            changed = rows.filter { it.merchantName == name }.mapNotNull { recategorise(it, to) },
            taught = rawNames.filter { it.isNotEmpty() && it !in learned }.sorted()
                .map { taught(it, name, to) },
        )
    }

    /**
     * A row learned from the user naming the category, with no payment
     * behind it — the same row `Repo.recordConfirmation` writes when it is
     * given no amount and no time. One confirmation and no samples puts it
     * below the auto-file line, so the next payment is filed under [to] and
     * asked about once rather than trusted on evidence that does not exist.
     */
    fun taught(rawName: String, name: String, to: String) = LearningRow(
        rawName = rawName,
        merchantName = name,
        categoryId = to,
        confirmationCount = 1,
        hourHistogram = List(24) { 0 },
    )

    /** Past payments currently filed under one category, that an explicit
     *  "move these too" would change. [from] null is uncategorised. */
    data class RefileGroup(val from: String?, val ids: List<String>)

    /**
     * What moving [name]'s past payments to [to] could touch, one group per
     * category they sit in now.
     *
     * An offer, never a consequence. Changing or forgetting a mapping changes
     * what happens to the NEXT payment only; the ledger is rewritten solely by
     * the groups the user ticks. Grouped rather than lumped, because some of
     * those payments may have been filed where they are by hand — a store
     * that sells both groceries and gifts — and one switch over all of them
     * would overwrite those decisions in bulk.
     *
     * Only rows on [side], the side of the ledger [to] belongs to: a refund
     * filed under an expense category would be counted as spending.
     */
    fun refileGroups(txs: List<Tx>, name: String, to: String, side: String): List<RefileGroup> =
        txs.filter { it.merchantName == name && it.type == side && it.categoryId != to }
            .groupBy { it.categoryId }
            .map { (from, rows) -> RefileGroup(from, rows.map { it.id }) }
            .sortedWith(compareByDescending<RefileGroup> { it.ids.size }
                .thenBy { it.from == null }
                .thenBy { it.from ?: "" })
}
