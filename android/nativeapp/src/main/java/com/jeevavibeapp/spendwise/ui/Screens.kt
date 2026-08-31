package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.*
import com.jeevavibeapp.spendwise.data.QuarantineEntity
import com.jeevavibeapp.spendwise.data.TransactionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The screens. Composed straight from Room Flows — no HTTP, no server, no
 * templates. What used to be a network round-trip through a Python WSGI
 * dispatch is now a function call, which is the entire reason the app starts
 * instantly instead of showing a splash while an engine boots.
 */

/** Not material3's `Card`: this one is flat, takes the theme's own radius and
 *  has no elevation. Named apart so the star import cannot be mistaken for it. */
@Composable
private fun ScreenCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = SectionHeading,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 26.dp, bottom = 10.dp),
    )
}

@Composable
fun DashboardScreen(
    balance: Double, income: Double, expense: Double,
    insights: List<String>, recurring: List<Recurring>,
    onAdd: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item {
            // The one loud thing on the screen. Everything else is whispered.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(Tokens.cardRadius),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(22.dp)) {
                    Text("TOTAL BALANCE", style = MicroLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("₹", style = MoneyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        // money() puts the sign ahead of the symbol ("-₹40"), so the
                        // symbol has to be split off rather than trimmed — and the
                        // sign belongs with the digits, at the digits' size.
                        val amount = Insights.money(balance)
                        Text(
                            (if (balance < 0) "−" else "") + amount.substringAfter('₹'),
                            style = MoneyLarge,
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("↓ INCOME", Insights.money(income), Modifier.weight(1f))
                        StatPill("↑ SPENT", Insights.money(expense), Modifier.weight(1f))
                    }
                }
            }
        }

        // A zero balance with nothing under it looks like a broken screen
        // rather than a new one, and the only manual entry point is a "+" the
        // first-run user has no reason to have found yet.
        if (income == 0.0 && expense == 0.0 && recurring.isEmpty()) {
            item {
                ScreenCard {
                    Text("Nothing recorded yet",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Bank messages are read on this device and filed here as " +
                        "they arrive. You can also enter one yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = onAdd,
                        modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                    ) { Text("Add a transaction") }
                }
            }
        }

        if (insights.isNotEmpty()) {
            item { SectionHeader("Insights") }
            item {
                // One card of hairline rows, not one card per sentence. A
                // repeated icon beside each line carries no information.
                ScreenCard {
                    insights.forEachIndexed { i, line ->
                        if (i > 0) HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 12.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 7.dp).size(5.dp)
                                .clip(RoundedCornerShape(3.dp)).background(PrimaryBright))
                            Spacer(Modifier.width(12.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (recurring.isNotEmpty()) {
            item { SectionHeader("Upcoming bills") }
            items(recurring) { r ->
                ScreenCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium)
                            Text("${r.cadence} · in ${r.daysLeft}d",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(Insights.money(r.amount), style = MoneyRow)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier,
    ) {
        Column(Modifier.padding(11.dp, 10.dp)) {
            Text(label, style = MicroLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
            Text(value, style = MoneyMedium)
        }
    }
}

@Composable
fun TransactionsScreen(
    transactions: List<TransactionEntity>,
    query: String,
    onQuery: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = onQuery,
            placeholder = { Text("Search merchant, notes, reference") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
                .padding(Tokens.screenPadding, 8.dp)
                .heightIn(min = Tokens.minTouchTarget),
        )
        if (transactions.isEmpty()) {
            // Two different situations, two different messages. Telling
            // someone with a full ledger "no transactions yet" because their
            // search missed claims their data is gone.
            EmptyState(
                title = if (query.isBlank()) "No transactions yet" else "No matches",
                body = if (query.isBlank())
                    "Bank messages are captured automatically — or add one yourself."
                else "Nothing matches “$query”. Your other transactions are still there.",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(Tokens.screenPadding, 4.dp,
                    Tokens.screenPadding, 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(transactions, key = { it.id }) { t -> TransactionRow(t, onDelete) }
            }
        }
    }
}

private val DayMonthLabel: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DayMonthYearLabel: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** The year is only shown when it is not this one: "12 Mar" on a row from two
 *  years ago reads as this March. */
private fun rowDate(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.format(
        if (date.year == LocalDate.now().year) DayMonthLabel else DayMonthYearLabel)
}

private fun sourceLabel(source: String): String = when (source) {
    "sms" -> "From SMS"
    "import" -> "Imported"
    "restore" -> "Restored"
    else -> "Added by you"
}

@Composable
private fun TransactionRow(t: TransactionEntity, onDelete: (String) -> Unit) {
    // Reference, note and delete are per-row detail: hanging them off every
    // row permanently would turn the ledger into a wall, and a delete icon
    // repeated down the list is decoration until the moment it is needed.
    var open by remember(t.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.rowRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.clickable { open = !open }) {
            Row(
                Modifier.heightIn(min = Tokens.minTouchTarget).padding(16.dp, 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(t.merchantName ?: t.rawMerchant ?: "Unknown",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val sub = buildString {
                        if (t.status != "confirmed") append("Needs review · ")
                        append(rowDate(t.occurredAt))
                        append(" · ")
                        append(sourceLabel(t.source))
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    (if (t.type == "income") "+" else "−") + Insights.money(t.amount),
                    style = MoneyRow,
                    color = if (t.type == "income") Income
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (open) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Column(Modifier.padding(16.dp, 12.dp)) {
                    t.referenceNumber?.let { DetailLine("Reference", it) }
                    t.notes?.let { DetailLine("Note", it) }
                    TextButton(
                        onClick = { onDelete(t.id) },
                        modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                    ) { Text("Delete", color = Expense) }
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.padding(bottom = 10.dp)) {
        Text(label.uppercase(), style = MicroLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HeldMessagesScreen(
    held: List<QuarantineEntity>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    if (held.isEmpty()) {
        EmptyState("Nothing held",
            "Every message we've seen came from a sender we could verify.")
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        items(held, key = { it.id }) { q ->
            ScreenCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(q.sender ?: "Unknown sender",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    Text("RISK ${q.risk}", style = MicroLabel, color = Expense)
                }
                q.reason?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (q.seenCount > 1) {
                    Spacer(Modifier.height(4.dp))
                    // Seeing the same scam repeatedly is itself information.
                    Text("Seen ${q.seenCount} times", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Approving files a transaction, so there has to be an
                    // amount to file. Without one, discard is the only move.
                    if (q.amount != null) {
                        Button(onClick = { onApprove(q.id) },
                            modifier = Modifier.weight(1f)
                                .heightIn(min = Tokens.minTouchTarget)) {
                            Text("It's real")
                        }
                    }
                    OutlinedButton(onClick = { onReject(q.id) },
                        modifier = Modifier.weight(1f)
                            .heightIn(min = Tokens.minTouchTarget)) {
                        Text("Discard")
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
