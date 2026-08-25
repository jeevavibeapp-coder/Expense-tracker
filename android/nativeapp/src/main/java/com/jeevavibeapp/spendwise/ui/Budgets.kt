package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Budget
import com.jeevavibeapp.spendwise.core.BudgetStatus
import com.jeevavibeapp.spendwise.core.Insights
import java.time.YearMonth
import java.util.Locale

/**
 * Categories and their monthly budgets.
 *
 * Every number on this screen is decided in :core and only drawn here — the
 * percentages, the remaining amounts and the over/near/ok verdict all arrive
 * on [BudgetStatus] already computed and already tested on the JVM. The one
 * thing this file is allowed to decide is what a control looks like.
 *
 * The screen is written against one rule taken from the core module: an
 * overspend is stated, never softened. The bar cannot draw past its own
 * width, so being 28% over and being exactly on the limit look identical in
 * the bar — which is why the amount over is always written out beside it.
 */
@Composable
fun BudgetsScreen(
    categories: List<Budget>,
    status: List<BudgetStatus>,
    month: YearMonth,
    incomeCategories: List<Budget> = emptyList(),
    onSetBudget: (categoryId: String, amount: Double?) -> Unit,
) {
    if (categories.isEmpty() && incomeCategories.isEmpty()) {
        EmptyState(
            "No categories yet",
            "Categories are created as your spending is filed. Once there is " +
            "one here, you can give it a monthly budget.",
        )
        return
    }

    // Only one editor open at a time: two half-typed budgets on screen is a
    // question about which one the keyboard belongs to.
    var editing by remember { mutableStateOf<String?>(null) }
    val rows = remember(categories, status) { displayRows(categories, status) }

    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item { SectionLabel("Spending · ${monthLabel(month)}") }

        items(rows, key = { it.categoryId }) { row ->
            BudgetRow(
                row = row,
                editing = editing == row.categoryId,
                onEdit = { editing = row.categoryId },
                onCancel = { editing = null },
                onSave = { amount ->
                    onSetBudget(row.categoryId, amount)
                    editing = null
                },
            )
        }

        if (incomeCategories.isNotEmpty()) {
            item { SectionLabel("Income") }
            items(incomeCategories, key = { it.categoryId }) { c ->
                // No budget, no bar, no spend. Money coming in is not
                // something anyone sets a limit on, and drawing the same card
                // here would imply it is.
                CardSurface {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(categoryColor(c.color, MaterialTheme.colorScheme.primary))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text("INCOME", style = MicroLabel, color = Income)
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetRow(
    row: BudgetStatus,
    editing: Boolean,
    onEdit: () -> Unit,
    onCancel: () -> Unit,
    onSave: (Double?) -> Unit,
) {
    // Re-seeded whenever the saved budget changes, so the field shows what is
    // stored rather than what was last typed into it.
    var draft by remember(row.categoryId, row.budget) { mutableStateOf(draftFor(row.budget)) }
    var rejected by remember(row.categoryId) { mutableStateOf(false) }
    val accent = statusColor(row)

    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The dot stays the category's own colour even when the bar has
            // gone red: it is how the row is recognised, not how it is doing.
            Dot(categoryColor(row.color, MaterialTheme.colorScheme.primary))
            Spacer(Modifier.width(12.dp))
            Text(row.name, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                if (row.budget != null)
                    "${Insights.money(row.spent)} / ${Insights.money(row.budget!!)}"
                else Insights.money(row.spent),
                style = MoneyRow,
                color = if (row.state == "over") Expense else MaterialTheme.colorScheme.onSurface,
            )
        }

        if (row.budget != null) {
            Spacer(Modifier.height(12.dp))
            BudgetBar(row.fraction.toFloat(), accent)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    standing(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.state == "over") Expense
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                row.pct?.let {
                    Text("$it%", style = MicroLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                if (row.spent > 0) "Spent so far this month. No budget set."
                else "Nothing spent this month, and no budget set.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (editing) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; rejected = false },
                label = { Text("Monthly budget (₹)") },
                placeholder = { Text("e.g. 5000") },
                singleLine = true,
                isError = rejected,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            )
            if (rejected) {
                Spacer(Modifier.height(6.dp))
                // A budget of zero is not a budget, and saving it as one would
                // put the category permanently over its limit.
                Text("Enter an amount above zero. To stop tracking this " +
                     "category, remove the budget instead.",
                    style = MaterialTheme.typography.bodySmall, color = Expense)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val amount = parseAmount(draft)
                        if (amount == null) rejected = true else onSave(amount)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
                ) { Text("Save budget") }
                OutlinedButton(
                    onClick = { draft = draftFor(row.budget); rejected = false; onCancel() },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Cancel") }
            }
            if (row.budget != null) {
                TextButton(
                    onClick = { onSave(null) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Expense),
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Remove budget") }
            }
        } else {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onEdit,
                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
            ) { Text(if (row.budget != null) "Change budget" else "Set a budget") }
        }
    }
}

/** The sentence beside the bar. Over budget gets the amount over, because
 *  that is the number the user is actually being told. */
private fun standing(row: BudgetStatus): String = when {
    row.state == "over" -> "${Insights.money(row.overBy ?: 0.0)} over budget"
    row.remaining != null -> "${Insights.money(row.remaining!!)} left this month"
    else -> ""
}

@Composable
private fun statusColor(row: BudgetStatus): Color = when (row.state) {
    "over" -> Expense
    "near" -> Warn
    else -> categoryColor(row.color, MaterialTheme.colorScheme.primary)
}

@Composable
private fun BudgetBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.18f)),
    ) {
        val filled = fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            Box(
                Modifier.fillMaxWidth(filled).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
}

@Composable
private fun CardSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = SectionHeading,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 2.dp),
    )
}

/**
 * Every category, ranked as :core ranked it, with the quiet ones after.
 *
 * A category with neither a budget nor any spending is absent from the status
 * list — there is genuinely nothing to report about it. It still has to be on
 * this screen, because this is the screen where its first budget gets set.
 */
private fun displayRows(categories: List<Budget>, status: List<BudgetStatus>): List<BudgetStatus> {
    val reported = status.mapTo(HashSet()) { it.categoryId }
    return status + categories.filter { it.categoryId !in reported }.map {
        BudgetStatus(
            categoryId = it.categoryId, name = it.name, color = it.color,
            budget = null, spent = 0.0, pct = null, remaining = null,
            overBy = null, fraction = 0.0, state = "unbudgeted",
        )
    }
}

/** What goes in the field: a plain number, not a formatted amount. Grouping
 *  separators and a currency symbol are things the user would have to delete
 *  before they could type. */
private fun draftFor(amount: Double?): String = when {
    amount == null -> ""
    amount % 1.0 == 0.0 -> amount.toLong().toString()
    else -> String.format(Locale.US, "%.2f", amount)
}

/** Accepts what people actually type — "5,000", "₹5000", "5000.50" — and
 *  nothing that is not a positive amount. */
private fun parseAmount(raw: String): Double? =
    raw.filter { it.isDigit() || it == '.' }
        .toDoubleOrNull()
        ?.takeIf { it > 0 && it.isFinite() }

private fun monthLabel(month: YearMonth): String =
    month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)

/** `#rrggbb` from the category row. Anything else falls back rather than
 *  throwing: a colour is decoration, and no decoration is worth a crash on
 *  the screen that lists the user's categories. */
private fun categoryColor(hex: String, fallback: Color): Color {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return fallback
    val rgb = digits.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or rgb)
}
