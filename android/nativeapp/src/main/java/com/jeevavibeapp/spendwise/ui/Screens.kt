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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            item {
                // One card for the same reason the insights get one: a card
                // each turns four bills into four things competing with the
                // balance for the eye.
                ScreenCard {
                    recurring.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(dueLabel(r), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(Insights.money(r.amount), style = MoneyRow)
                        }
                    }
                }
            }
        }
    }
}

/**
 * When the bill is due, in words.
 *
 * `daysLeft` is negative for up to a full period before :core gives up on a
 * bill, so the obvious "in ${daysLeft}d" prints "in -12d" on a subscription
 * that has not been charged on time — and an overdue bill is the one on this
 * list worth reading.
 */
private fun dueLabel(r: Recurring): String {
    val cadence = when (r.cadence) {
        "weekly" -> "Weekly"
        "monthly" -> "Monthly"
        "quarterly" -> "Quarterly"
        else -> r.cadence
    }
    val due = when {
        r.daysLeft < -1 -> "was due ${-r.daysLeft} days ago"
        r.daysLeft == -1L -> "was due yesterday"
        r.daysLeft == 0L -> "due today"
        r.daysLeft == 1L -> "due tomorrow"
        else -> "due in ${r.daysLeft} days"
    }
    return "$cadence · $due"
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
        // Nothing to search until there is something in the ledger, and a
        // search box over an empty screen is a control that cannot do
        // anything. It stays once a query is typed, or clearing the last
        // non-matching search would take the field away with it.
        if (transactions.isNotEmpty() || query.isNotBlank()) {
            OutlinedTextField(
                value = query, onValueChange = onQuery,
                placeholder = { Text("Search merchant, notes, reference") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .padding(Tokens.screenPadding, 8.dp)
                    .heightIn(min = Tokens.minTouchTarget),
            )
        }
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

/** The score at which :core's own sender rules treat content as a scam.
 *  Below it a message can still be held — an unfamiliar sender, or one the
 *  user blocked — so this decides how loudly the card speaks, not whether
 *  the message is on it. */
private const val HIGH_RISK = 70

@Composable
fun HeldMessagesScreen(
    held: List<QuarantineEntity>,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    if (held.isEmpty()) {
        // What this screen actually knows is that nothing is waiting. It does
        // not know that every message was checked — SMS access may never have
        // been granted, or not be in this build at all — so it does not say so.
        EmptyState(
            "Nothing held",
            "Bank messages that look wrong are kept here until you say what " +
            "they are. None are waiting.",
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        items(held, key = { it.id }) { q -> HeldCard(q, onApprove, onReject) }
    }
}

@Composable
private fun HeldCard(
    q: QuarantineEntity,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
) {
    // The message is the evidence, and judging it without reading it is
    // guessing — but a full SMS above the buttons pushes them off the screen,
    // so it opens on demand the way a ledger row does.
    var open by remember(q.id) { mutableStateOf(false) }
    ScreenCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q.sender ?: "Unknown sender",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            q.amount?.let {
                Spacer(Modifier.width(8.dp))
                // The amount "It's real" would file, on the card where that
                // decision is made.
                Text(Insights.money(it), style = MoneyRow)
            }
        }
        q.reason?.let { reason ->
            Spacer(Modifier.height(6.dp))
            // :core already writes the specific sentence — "This message asks
            // for an OTP, PIN or card detail" — so the sentence is the
            // warning and its weight follows the score. The score itself
            // never reaches the screen: a sender the user blocked is held at
            // risk 0, which printed literally put "RISK 0" in alarm red.
            val severe = q.risk >= HIGH_RISK
            Text(
                reason,
                style = if (severe) MaterialTheme.typography.bodyMedium
                        else MaterialTheme.typography.bodySmall,
                color = if (severe) Expense else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (q.seenCount > 1) {
            Spacer(Modifier.height(4.dp))
            // Seeing the same scam repeatedly is itself information.
            Text("Seen ${q.seenCount} times", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(Tokens.rowRadius),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tokens.minTouchTarget)
                .clickable(
                    onClickLabel = if (open) "Shorten the message" else "Read the whole message",
                ) { open = !open },
        ) {
            Text(
                q.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Approving files a transaction, so there has to be an amount to
            // file. Without one, discard is the only move.
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

@Composable
fun EmptyState(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        // Centred with the title. Left-aligned lines under a centred heading
        // read as a layout that gave up halfway.
        Text(body, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}
