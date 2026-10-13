package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.core.Review
import com.jeevavibeapp.spendwise.data.CategoryEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Sorting a week of captures, one payee at a time.
 *
 * Reviewing hundreds of captures one row at a time is not something anyone
 * does — but they come from far fewer payees, so the queue is asked as one
 * question per payee and each answer clears every row behind it. The
 * grouping, the ordering and the pre-filled suggestion are all decided in
 * :core; this file only draws them.
 */

@Composable
fun ReviewScreen(
    groups: List<Review.Group>,
    categories: List<CategoryEntity>,
    onCategorise: (Review.Group, String) -> Unit,
    onCreateCategory: (Review.Group, String) -> Unit,
    onDiscard: (Review.Group) -> Unit,
) {
    if (groups.isEmpty()) {
        EmptyState(
            "All caught up",
            "Nothing is waiting to be sorted. Captures the app is unsure about " +
            "collect here, grouped by who they are from.",
        )
        return
    }
    var discarding by remember { mutableStateOf<Review.Group?>(null) }
    val names = remember(categories) { categories.associateBy({ it.id }, { it.name }) }

    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item {
            // What the screen is for, said once. Without it the first card
            // looks like an ordinary transaction with an unusual number of
            // buttons, and the thing that makes it worth opening — that one
            // tap settles all of them — is invisible.
            Text(
                "One tap sorts every capture from that payee, and teaches SpendWise " +
                "to file the next one on its own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        items(groups, key = { it.groupId }) { group ->
            GroupCard(
                group = group,
                categories = categories,
                suggestedName = group.suggestedCategoryId?.let { names[it] },
                onCategorise = onCategorise,
                onCreateCategory = onCreateCategory,
                onDiscard = { discarding = group },
            )
        }
    }

    discarding?.let { group ->
        // A bulk delete is the one action here that cannot be answered again
        // later, so it is asked for twice. The rows are soft-deleted, but
        // nothing on this screen offers them back.
        AlertDialog(
            onDismissRequest = { discarding = null },
            title = { Text("Remove ${group.count} ${plural(group.count, "capture", "captures")}?") },
            text = {
                Text(
                    "Everything from “${payeeLabel(group)}” is removed from your ledger. " +
                    "Use this for messages that were never transactions.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { discarding = null; onDiscard(group) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Expense),
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Remove all ${group.count}") }
            },
            dismissButton = {
                TextButton(
                    onClick = { discarding = null },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Keep them") }
            },
        )
    }
}

@Composable
private fun GroupCard(
    group: Review.Group,
    categories: List<CategoryEntity>,
    suggestedName: String?,
    onCategorise: (Review.Group, String) -> Unit,
    onCreateCategory: (Review.Group, String) -> Unit,
    onDiscard: () -> Unit,
) {
    var typed by remember(group.groupId) { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(payeeLabel(group), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle(group), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (group.type == "income") "+" else "−") + Insights.money(group.total),
                    style = MoneyRow,
                    color = if (group.type == "income") Income
                            else MaterialTheme.colorScheme.onSurface,
                )
            }

            val suggestedId = group.suggestedCategoryId
            if (suggestedName != null && suggestedId != null) {
                Spacer(Modifier.height(14.dp))
                // Offered as its own button rather than pre-selected among
                // the chips: a category applied silently is one the user
                // never sees to disagree with.
                Button(
                    onClick = { onCategorise(group, suggestedId) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("$suggestedName · ${group.suggestedConfidence}%") }
                if (group.because.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Suggested from “${group.because.joinToString("”, “")}” in what " +
                        "you have already sorted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                offered(categories, group.type).forEach { c ->
                    // No chip is ever shown as selected: none of these rows
                    // has an answer yet, and a highlighted chip would claim
                    // one of them does.
                    AssistChip(
                        onClick = { onCategorise(group, c.id) },
                        label = { Text(c.name) },
                        modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Or a new category…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
                )
                TextButton(
                    onClick = { if (typed.isNotBlank()) onCreateCategory(group, typed) },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Create") }
            }

            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onDiscard,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) {
                Text("Not a transaction — remove all ${group.count}", color = Expense)
            }
        }
    }
}

/** Captures that resolved to no payee at all still have to be nameable, or
 *  the group holding the likeliest junk in the queue is the one the screen
 *  cannot label. */
private fun payeeLabel(group: Review.Group): String =
    group.key.ifBlank { "Unknown payee" }

private val SpanDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun subtitle(group: Review.Group): String = buildString {
    append(group.count)
    append(' ')
    append(plural(group.count, "capture", "captures"))
    span(group.firstAt, group.lastAt)?.let { append(" · "); append(it) }
    group.sender?.let { append(" · "); append(it) }
}

/** One date when everything landed the same day: "1 Jun → 1 Jun" is a range
 *  that describes a single day in twice the space. */
private fun span(first: LocalDateTime?, last: LocalDateTime?): String? {
    if (first == null || last == null) return null
    val from = first.format(SpanDate)
    val to = last.format(SpanDate)
    return if (from == to) from else "$from → $to"
}

/**
 * The categories offered for a group's type.
 *
 * Falling back to all of them matters more than the filter does: a ledger
 * whose categories are all seeded as expenses would otherwise offer nothing
 * at all on a group of captured income.
 */
private fun offered(all: List<CategoryEntity>, type: String): List<CategoryEntity> {
    val matching = all.filter { it.type == type }
    return if (matching.isEmpty()) all else matching
}

private fun plural(count: Int, one: String, many: String): String =
    if (count == 1) one else many
