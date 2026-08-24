package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.*
import com.jeevavibeapp.spendwise.data.TransactionEntity
import java.time.YearMonth

/**
 * The screens. Composed straight from Room Flows — no HTTP, no server, no
 * templates. What used to be a network round-trip through a Python WSGI
 * dispatch is now a function call, which is the entire reason the app starts
 * instantly instead of showing a splash while an engine boots.
 */

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
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
                        Text("₹", fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                            style = MoneyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(Insights.money(balance).removePrefix("₹"), style = MoneyLarge)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatPill("↓ INCOME", Insights.money(income), Modifier.weight(1f))
                        StatPill("↑ SPENT", Insights.money(expense), Modifier.weight(1f))
                    }
                }
            }
        }

        if (insights.isNotEmpty()) {
            item { SectionHeader("Insights") }
            item {
                // One card of hairline rows, not one card per sentence. A
                // repeated icon beside each line carries no information.
                Card {
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
                Card {
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
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline),
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

@Composable
private fun TransactionRow(t: TransactionEntity, onDelete: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.rowRadius),
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
    ) {
        Row(
            Modifier.padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(t.merchantName ?: t.rawMerchant ?: "Unknown",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1)
                val sub = buildString {
                    if (t.status != "confirmed") append("Needs review · ")
                    append(if (t.source == "sms") "From SMS" else "Added by you")
                }
                Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                (if (t.type == "income") "+" else "−") + Insights.money(t.amount),
                style = MoneyRow,
                color = if (t.type == "income") Income else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun HeldMessagesScreen(
    held: List<com.jeevavibeapp.spendwise.data.QuarantineEntity>,
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
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(q.sender ?: "Unknown sender",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
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
                    if (q.amount != null) {
                        Button(onClick = { onApprove(q.id) },
                            modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget)) {
                            Text("It's real")
                        }
                    }
                    OutlinedButton(onClick = { onReject(q.id) },
                        modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget)) {
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
