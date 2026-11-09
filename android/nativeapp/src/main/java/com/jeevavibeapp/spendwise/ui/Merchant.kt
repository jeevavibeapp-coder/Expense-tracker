package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.core.MerchantBook
import com.jeevavibeapp.spendwise.core.Tx
import com.jeevavibeapp.spendwise.data.CategoryEntity
import com.jeevavibeapp.spendwise.data.Repo
import com.jeevavibeapp.spendwise.data.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One merchant: what was spent there, how that moves month to month, and
 * what SpendWise has learned about it, with a way to change or forget that.
 *
 * Every figure and every rule is MerchantBook in :core. This file draws them
 * and hands decisions back as callbacks. The learning card is why the page
 * exists. A wrong mapping misfiles every new capture from a payee, and before
 * this page nothing on screen showed one.
 */

/** A zero month keeps a visible stub, so a quiet month reads as a fact on
 *  the chart rather than as a month the chart forgot. */
private const val MIN_BAR_FRACTION = 0.04f

/** Spellings listed by name before the rest are counted. A card that lists
 *  forty raw strings buries the one row worth reading. */
private const val SPELLINGS_SHOWN = 4

private val ShortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val LongDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * @param ledger the whole ledger as :core reads it. The page picks this
 *   merchant out of it rather than querying again, so it lists exactly the
 *   payments the report row that opened it summed.
 * @param transactions the same rows as stored, for the list and the editor.
 * @param filingFor the live learning for this merchant's raw spellings,
 *   normally `{ repo.merchantFiling(name, it) }`.
 * @param onTransaction open one payment in the editor.
 * @param onFileUnder file new payments under a category, and move the past
 *   payments whose ids are given. The ids are only ever ones the user ticked.
 * @param onForget remove one learned mapping. The ledger is not touched.
 */
@Composable
fun MerchantScreen(
    name: String,
    ledger: List<Tx>,
    transactions: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    today: LocalDate,
    filingFor: (Set<String>) -> Flow<MerchantBook.Filing>,
    onTransaction: (TransactionEntity) -> Unit,
    onFileUnder: (categoryId: String, rawNames: Set<String>, moveIds: List<String>) -> Unit,
    onForget: (MerchantBook.Mapping) -> Unit,
) {
    // Off the main thread. Picking the merchant out walks the whole ledger,
    // and its spellings are folded with the resolver's regexes once per
    // payment. Neither belongs in a frame.
    var page by remember(name) { mutableStateOf<MerchantPage?>(null) }
    LaunchedEffect(name, ledger, transactions, today) {
        page = withContext(Dispatchers.Default) { merchantPage(name, ledger, transactions, today) }
    }
    val p = page
    val keys = p?.rawNames
    val source: Flow<MerchantBook.Filing?> = remember(name, keys) {
        if (keys == null) flowOf(null) else filingFor(keys)
    }
    val filing by source.collectAsState(initial = null)
    val names = remember(categories) { categories.associateBy({ it.id }, { it.name }) }
    var choosing by remember(name) { mutableStateOf(false) }

    if (p == null) {
        // One frame, normally. Drawing zeros here would flash "₹0 spent" at
        // someone who has spent thousands.
        Box(Modifier.fillMaxSize())
        return
    }
    val s = p.summary

    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item { Hero(s) }

        if (s.hasTrend) {
            item { MerchantHeading("Month by month") }
            item { MonthBars(s) }
        }

        item { MerchantHeading("What SpendWise has learned") }
        item {
            LearningCard(
                name = name,
                filing = filing,
                names = names,
                onChange = { choosing = true },
                onForget = onForget,
            )
        }

        if (p.rows.isNotEmpty()) {
            item { MerchantHeading("Transactions") }
            items(p.rows, key = { it.id }) { t -> PaymentRow(t, names, onTransaction) }
        }
    }

    val f = filing
    if (choosing && f != null) {
        FileUnderDialog(
            name = name,
            side = s.side,
            categories = categories,
            current = f.categories,
            mine = p.mine,
            names = names,
            onDismiss = { choosing = false },
            onSave = { categoryId, moveIds ->
                choosing = false
                onFileUnder(categoryId, p.rawNames, moveIds)
            },
        )
    }
}

/** Everything the page shows that the ledger decides, worked out at once. */
private class MerchantPage(
    val summary: MerchantBook.Summary,
    val rows: List<TransactionEntity>,
    /** This merchant's payments as :core reads them, for the move offer. */
    val mine: List<Tx>,
    val rawNames: Set<String>,
)

private fun merchantPage(
    name: String,
    ledger: List<Tx>,
    transactions: List<TransactionEntity>,
    today: LocalDate,
): MerchantPage {
    val summary = MerchantBook.summary(ledger, name, today)
    val wanted = summary.ids.toHashSet()
    val byId = transactions.filter { it.id in wanted }.associateBy { it.id }
    val rows = summary.ids.mapNotNull { byId[it] }
    return MerchantPage(
        summary = summary,
        rows = rows,
        mine = ledger.filter { it.id in wanted },
        // The string Repo.save teaches under, so these are the keys the next
        // payment is looked up by.
        rawNames = MerchantBook.rawNames(rows.map { it.rawMerchant ?: it.merchantName }),
    )
}

@Composable
private fun MerchantHeading(text: String) {
    Text(
        text.uppercase(),
        style = SectionHeading,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp).semantics { heading() },
    )
}

@Composable
private fun MerchantCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun Hero(s: MerchantBook.Summary) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(10.dp))
            Text("SPENT HERE", style = MicroLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            // headlineLarge rather than the report's 44sp hero figure: at the
            // largest font scale that one wraps a six-figure sum mid-number.
            Text(Insights.money(s.spent), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(14.dp))
            // One fact per full-width line rather than three columns, which
            // is what stays readable when the font is scaled up.
            Fact("Payments", s.payments.toString())
            if (s.payments > 1) Fact("Average payment", Insights.money(s.avgPayment))
            if (s.received > 0) Fact("Received back", Insights.money(s.received))
            s.last?.let { Fact("Last", dateLabel(it)) }
            val first = s.first
            if (first != null && first != s.last) Fact("First", dateLabel(first))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    // Merged, so a screen reader says "Payments, 12" as one thing instead of
    // two unrelated words.
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MoneyRow)
    }
}

@Composable
private fun MonthBars(s: MerchantBook.Summary) {
    val peak = s.months.maxOf { it.amount }
    val top = s.months.lastOrNull { it.amount == peak }
    // The bars carry no numbers, so the screen reader gets them all at once,
    // in order, instead of six unlabeled shapes.
    val spoken = "Spent at ${s.name} by month: " +
        s.months.joinToString(", ") { "${monthName(it.month)} ${Insights.money(it.amount)}" }
    MerchantCard {
        Column(Modifier.clearAndSetSemantics { contentDescription = spoken }) {
            Row(
                Modifier.fillMaxWidth().height(80.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s.months.forEach { m ->
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        val fraction = if (peak > 0) (m.amount / peak).toFloat() else 0f
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceIn(MIN_BAR_FRACTION, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (m.amount > 0) Primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // Outside the fixed-height row, so a scaled-up label pushes the
            // card taller instead of squeezing the bars to nothing.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.months.forEach { m ->
                    Text(
                        m.month.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (top != null) {
                Spacer(Modifier.height(10.dp))
                Text("Most in ${monthName(top.month)}: ${Insights.money(top.amount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LearningCard(
    name: String,
    filing: MerchantBook.Filing?,
    names: Map<String, String>,
    onChange: () -> Unit,
    onForget: (MerchantBook.Mapping) -> Unit,
) {
    MerchantCard {
        if (filing == null) {
            Text("Reading what SpendWise has learned…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@MerchantCard
        }

        Text(filedSentence(name, filing, names), style = MaterialTheme.typography.bodyMedium)

        if (filing.canRelearn) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) {
                Text(if (filing.own.isEmpty()) "Choose a category for new payments"
                     else "Change category for new payments")
            }
        }

        filing.own.forEach { m ->
            MappingDivider()
            MappingRow(
                title = "“${m.rawName}”",
                detail = mappingDetail(m, names),
                onForget = { onForget(m) },
            )
        }

        // The misfiling seen from the side it takes payments away from.
        filing.elsewhere.forEach { m ->
            MappingDivider()
            MappingRow(
                title = "“${m.rawName}” is now named ${m.merchantName}",
                detail = "Some payments here arrived as “${m.rawName}”. New ones are filed " +
                    "as ${m.merchantName}, under ${categoryLabel(m.categoryId, names)}.",
                onForget = { onForget(m) },
            )
        }

        if (filing.builtIn.isNotEmpty() || filing.unlearned.isNotEmpty()) {
            MappingDivider()
            filing.builtIn.take(SPELLINGS_SHOWN).forEach { b ->
                Text("“${b.rawName}” isn't learned yet. SpendWise's built-in list files it " +
                     "as ${b.display}, under ${b.categoryName}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            if (filing.unlearned.isNotEmpty()) {
                Text(
                    "Not learned yet, so guessed each time: " +
                        spellings(filing.unlearned) + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val hidden = (filing.builtIn.size - SPELLINGS_SHOWN).coerceAtLeast(0)
            if (hidden > 0) {
                Text("$hidden more built-in spellings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(12.dp))
        // Said on the card, before anyone presses anything: the fear this
        // answers is "will fixing this rewrite my history?"
        Text("Changing or forgetting this affects new payments only. Past payments keep " +
             "their category unless you choose to move them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MappingDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun MappingRow(title: String, detail: String, onForget: () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(2.dp))
    Text(detail, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    // Below the text rather than beside it: beside it, a scaled-up label
    // squeezes the detail into a column one word wide.
    TextButton(
        onClick = onForget,
        // Several rows can each have a Forget button, and "Forget, button"
        // three times over tells a screen reader user nothing.
        modifier = Modifier
            .heightIn(min = Tokens.minTouchTarget)
            .semantics { contentDescription = "Forget $title" },
    ) { Text("Forget") }
}

/** How new payments are filed, in one sentence, from whichever evidence decides it. */
private fun filedSentence(name: String, f: MerchantBook.Filing, names: Map<String, String>): String {
    val cats = f.categories.map { categoryLabel(it, names) }.distinct()
    return when {
        cats.size == 1 -> "New payments from $name are filed under ${cats[0]}."
        cats.size > 1 -> "New payments from $name are filed under ${cats.joinToString(" or ")}, " +
            "depending on how the bank spells the name."
        f.builtIn.isNotEmpty() -> "Nothing learned yet. SpendWise's built-in list files new " +
            "payments under ${f.builtIn.map { it.categoryName }.distinct().joinToString(" or ")}."
        f.unlearned.isNotEmpty() -> "Nothing learned yet, so each new payment is a guess until " +
            "you confirm or edit one."
        else -> "Nothing has been learned for this name."
    }
}

private fun mappingDetail(m: MerchantBook.Mapping, names: Map<String, String>): String = buildString {
    append(categoryLabel(m.categoryId, names))
    append(" · confirmed ").append(times(m.confirmations))
    if (m.corrections > 0) append(" · corrected ").append(times(m.corrections))
    val lo = m.usualMin
    val hi = m.usualMax
    if (lo != null && hi != null) {
        append(" · usually ")
        append(if (lo == hi) Insights.money(lo) else "${Insights.money(lo)} to ${Insights.money(hi)}")
    }
    append(" · ${m.confidence}% sure")
}

/** Spelled out: a screen reader reads "12×" as "12 multiplication sign". */
private fun times(n: Int): String = if (n == 1) "once" else "$n times"

private fun spellings(raw: List<String>): String {
    val shown = raw.take(SPELLINGS_SHOWN).joinToString(", ") { "“$it”" }
    val rest = raw.size - SPELLINGS_SHOWN
    return if (rest > 0) "$shown and $rest more" else shown
}

/** Null is a real answer, not a missing one, and an id the category list no
 *  longer holds still has to be nameable. */
private fun categoryLabel(id: String?, names: Map<String, String>): String =
    if (id == null) "no category" else names[id] ?: "a category that no longer exists"

@Composable
private fun PaymentRow(
    t: TransactionEntity,
    names: Map<String, String>,
    onOpen: (TransactionEntity) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.rowRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Edit this transaction", role = Role.Button) { onOpen(t) }
                .heightIn(min = Tokens.minTouchTarget)
                .padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(dateLabel(Repo.localTime(t.occurredAt).toLocalDate()),
                    style = MaterialTheme.typography.titleMedium)
                // The category is the point of listing them here: a run of
                // payments filed somewhere wrong is how a bad mapping shows.
                val sub = listOfNotNull(
                    "Needs review".takeIf { t.status != "confirmed" },
                    categoryLabel(t.categoryId, names).replaceFirstChar { it.uppercase() },
                    t.notes ?: t.referenceNumber?.let { "Ref $it" },
                ).joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (if (t.type == "income") "+" else "−") + Insights.money(t.amount),
                style = MoneyRow,
                color = if (t.type == "income") Income else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * Where new payments go, and which past ones go with them.
 *
 * Past payments are listed one group per category they sit in now, every box
 * unticked. Nothing in the ledger moves unless a box is ticked, and a group
 * the user filed by hand can be left where it is.
 */
@Composable
private fun FileUnderDialog(
    name: String,
    side: String,
    categories: List<CategoryEntity>,
    current: Set<String?>,
    mine: List<Tx>,
    names: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (categoryId: String, moveIds: List<String>) -> Unit,
) {
    // Falls back to every category, as the other pickers do: a ledger whose
    // categories are all seeded as expenses would otherwise offer an
    // employer nothing at all.
    val offered = remember(categories, side) {
        categories.filter { it.type == side }.ifEmpty { categories }
    }
    var chosen by remember {
        mutableStateOf(current.singleOrNull()?.takeIf { id -> offered.any { it.id == id } })
    }
    var ticked by remember { mutableStateOf(emptySet<String?>()) }
    val target = chosen
    val groups = remember(target, mine, side) {
        if (target == null) emptyList() else MerchantBook.refileGroups(mine, name, target, side)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("File new payments from $name under") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    offered.forEach { c ->
                        FilterChip(
                            selected = c.id == target,
                            onClick = {
                                chosen = c.id
                                // A box ticked against another target is not
                                // a decision about this one.
                                ticked = emptySet()
                            },
                            label = { Text(c.name) },
                            modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                        )
                    }
                }
                if (groups.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("PAST PAYMENTS", style = MicroLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Left where they are unless you tick them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    groups.forEach { g ->
                        val on = g.from in ticked
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = Tokens.minTouchTarget)
                                .toggleable(value = on, role = Role.Checkbox) { now ->
                                    ticked = if (now) ticked + g.from else ticked - g.from
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The row is the control; the box only shows its
                            // state, so a screen reader announces one checkbox.
                            Checkbox(checked = on, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            Text(moveLabel(g, names), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val to = target ?: return@TextButton
                    onSave(to, groups.filter { it.from in ticked }.flatMap { it.ids })
                },
                enabled = target != null,
                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
            ) { Text("Cancel") }
        },
    )
}

private fun moveLabel(g: MerchantBook.RefileGroup, names: Map<String, String>): String {
    val n = g.ids.size
    val noun = if (n == 1) "payment" else "payments"
    return if (g.from == null) "Also move $n uncategorised $noun"
           else "Also move $n $noun filed under ${categoryLabel(g.from, names)}"
}

/** The year only when it is not this one: "12 Mar" on a row from two years
 *  ago reads as this March. */
private fun dateLabel(d: LocalDate): String =
    d.format(if (d.year == LocalDate.now().year) ShortDate else LongDate)

private fun monthName(m: YearMonth): String =
    m.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
