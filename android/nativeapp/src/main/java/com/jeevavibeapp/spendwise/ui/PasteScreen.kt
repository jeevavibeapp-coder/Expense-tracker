package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.core.Paste
import com.jeevavibeapp.spendwise.core.Pipeline
import com.jeevavibeapp.spendwise.data.CategoryEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Paste a bank message, see what SpendWise read, correct it, then save it.
 *
 * The only capture path the no-SMS build has. It used to put the message
 * straight into the ledger from the button that read it, so a misread amount,
 * day, direction or payee became a transaction nobody had looked at. Now
 * nothing is written until Save, and Save writes the card as it stands: the
 * card is drawn from [Paste.commit] of its own fields, which is the row the
 * repository stores, so the words under the fields describe what will land
 * rather than what the message started as.
 *
 * A page rather than a dialog because the card is a form: a tap beside a
 * dialog would throw away every correction on it, and back here unwinds the
 * page like any other.
 *
 * @param preview reads a message and stores nothing — normally
 *   `repo.previewPaste`. It suspends onto the IO dispatcher, so reading never
 *   runs on the main thread.
 * @param banked the row already filed under an unhashed dedup key. Asked
 *   again only when an undated message's day is changed, the one correction
 *   that moves its key.
 * @param onSave store the commit. The host runs it on a scope that outlives
 *   this page, so leaving straight after Save cannot cancel the write.
 * @param onRestore bring back the deleted row a message was filed as.
 * @param onUnreadable log the masked shape of a message the preview said it
 *   cannot read, so it reaches Settings' list of unreadable messages as a
 *   captured one would. True when a row was written; the card says so only then.
 */
@Composable
fun PasteScreen(
    categories: List<CategoryEntity>,
    preview: suspend (String) -> Paste.Preview,
    banked: suspend (String) -> Paste.Banked?,
    onSave: (Paste.Commit) -> Unit,
    onRestore: (Paste.Banked) -> Unit,
    onUnreadable: suspend (String) -> Boolean,
) {
    var text by remember { mutableStateOf("") }
    // The answer is kept beside the text it answers. Edit the message after
    // reading it and the card would otherwise describe a message no longer in
    // the box — and Save would store that one.
    var read by remember { mutableStateOf<Pair<String, Paste.Preview>?>(null) }
    var reading by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    // The text whose masked copy was logged, paired like [read] is.
    var logged by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val result = read?.takeIf { it.first == text }?.second
    val clear = { text = ""; read = null; attempted = false }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 32.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        PasteCard {
            Hint("It's read on this phone, by the same parser that reads captured " +
                 "messages. Nothing is saved until you have checked it and tapped Save.")
            Spacer(Modifier.height(12.dp))
            val missing = attempted && text.isBlank()
            val needed: (@Composable () -> Unit)? =
                if (missing) { { Text("Paste the message from your bank first") } } else null
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; attempted = false },
                label = { Text("Bank message") },
                minLines = 4,
                isError = missing,
                supportingText = needed,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    attempted = true
                    if (text.isBlank()) return@Button
                    val body = text
                    reading = true
                    scope.launch {
                        try {
                            val found = preview(body)
                            read = body to found
                            if (found is Paste.Preview.NotATransaction && onUnreadable(body)) {
                                logged = body
                            }
                        } finally { reading = false }
                    }
                },
                // Off only while a read is running: resolving the payee
                // retrains the categoriser over the whole ledger, and a second
                // tap would queue that again for the same answer.
                enabled = !reading,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) { Text(if (reading) "Reading…" else "Read it") }
        }

        when (result) {
            null -> Unit
            is Paste.Preview.NotATransaction ->
                NotATransactionCard(result.reason, logged = logged == text, onClear = clear)
            // Keyed on the preview so a second read starts its card from the
            // new draft rather than the last one's half-made corrections.
            is Paste.Preview.Transaction -> key(result) {
                TransactionCard(
                    found = result,
                    categories = categories,
                    banked = banked,
                    onSave = { commit -> onSave(commit); clear() },
                    onDiscard = clear,
                    onRestore = { row -> onRestore(row); clear() },
                )
            }
        }
    }
}

private val DayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransactionCard(
    found: Paste.Preview.Transaction,
    categories: List<CategoryEntity>,
    banked: suspend (String) -> Paste.Banked?,
    onSave: (Paste.Commit) -> Unit,
    onDiscard: () -> Unit,
    onRestore: (Paste.Banked) -> Unit,
) {
    val draft = found.draft
    val start = remember { Paste.fields(draft) }
    var amount by remember { mutableStateOf(editableAmount(start.amount)) }
    var type by remember { mutableStateOf(start.type) }
    var merchant by remember { mutableStateOf(start.merchant) }
    var categoryId by remember { mutableStateOf(start.categoryId) }
    var date by remember { mutableStateOf(start.date) }
    var showDatePicker by remember { mutableStateOf(false) }

    val typed = typedAmount(amount)
    val commit = typed?.let { Paste.commit(draft, Paste.Fields(it, type, merchant, categoryId, date)) }
    val amountError = when {
        commit != null -> null
        amount.isBlank() -> "Enter an amount"
        typed == null -> "Amounts look like 249 or 249.50"
        typed <= 0 -> "Amount has to be more than zero"
        else -> "That is more than any one payment can be. Check it against the message."
    }

    val key = commit?.dedupKey ?: draft.dedupKey
    var duplicate by remember { mutableStateOf(found.duplicate) }
    LaunchedEffect(key) {
        // Only an undated message's key moves, when its day is changed, so
        // this asks nothing new on an amount or a name being typed.
        duplicate = if (key == draft.dedupKey) found.duplicate else banked(key)
    }
    val dup = duplicate

    PasteCard {
        Text(
            when {
                dup?.deleted == true -> "You deleted this one before"
                dup != null -> "Already in your ledger"
                found.warning != null -> "Check this one before saving it"
                else -> "Here's what SpendWise read"
            },
            style = MaterialTheme.typography.titleMedium,
            // Announced as it appears: the card lands below a button the
            // screen reader is still focused on, and silence there reads as
            // the tap having done nothing.
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(6.dp))

        if (dup != null) {
            Hint("${dup.merchant ?: "Unknown payee"} · ${Insights.money(dup.amount)} · " +
                 dup.occurredAt.toLocalDate().format(DayLabel))
            Spacer(Modifier.height(4.dp))
            Hint(if (dup.deleted)
                "It was added from this message and then deleted, so it can't be added a " +
                "second time. Restore it to have it back as it was."
            else "Saving it again would add nothing: one bank message is one transaction.")
        }
        found.warning?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Expense)
            Spacer(Modifier.height(4.dp))
            Hint("If you were really charged, you can still save it.")
        }

        // An undated duplicate stays editable: its key is the day it is
        // filed under, so a different day is a different payment.
        if (dup == null || !draft.dated) {
            Spacer(Modifier.height(14.dp))
            val message: (@Composable () -> Unit)? = amountError?.let { e -> { Text(e) } }
            OutlinedTextField(
                value = amount,
                onValueChange = { input ->
                    if (input.all { it.isDigit() || it == '.' || it == ',' }) amount = input
                },
                label = { Text("Amount") },
                leadingIcon = { Text("₹") },
                singleLine = true,
                isError = amountError != null,
                supportingText = message,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                textStyle = MoneyMedium,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            )

            Spacer(Modifier.height(10.dp))
            // The direction is the misreading that costs most: a salary read
            // as a spend is off by twice its amount in every total.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf("expense" to "Expense", "income" to "Income")) {
                    FilterChip(
                        selected = type == value,
                        onClick = {
                            type = value
                            // A category belongs to one side of the ledger,
                            // and the chip for this one is about to vanish.
                            if (offeredFor(categories, value).none { it.id == categoryId }) {
                                categoryId = null
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            val raw = draft.rawMerchant
            val saidByBank: (@Composable () -> Unit)? =
                raw?.takeIf { !it.equals(merchant.trim(), ignoreCase = true) }?.let { r ->
                    { Text("The message says “$r”") }
                }
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Payee") },
                singleLine = true,
                supportingText = saidByBank,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            )

            Spacer(Modifier.height(14.dp))
            Text("CATEGORY", style = MicroLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            CategoryPicker(
                categories = categories,
                selectedId = categoryId,
                onSelect = { categoryId = it },
                type = type,
            )

            Spacer(Modifier.height(14.dp))
            Text("DATE", style = MicroLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val day = date.format(DayLabel)
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.minTouchTarget)
                    .semantics { contentDescription = "Date, $day. Change it" },
            ) { Text(day) }
            if (!draft.dated) {
                Spacer(Modifier.height(4.dp))
                Hint("The message doesn't say which day, so this is today. Change it if " +
                     "the payment was earlier.")
            }

            if (dup == null) {
                statusLine(found, commit)?.let {
                    Spacer(Modifier.height(14.dp))
                    Hint(it)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        val save = { if (commit != null) onSave(commit) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val wide = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget)
            when {
                dup?.deleted == true -> {
                    Button(onClick = { onRestore(dup) }, modifier = wide) { Text("Restore it") }
                    OutlinedButton(onClick = onDiscard, modifier = wide) { Text("Discard") }
                }
                dup != null -> OutlinedButton(onClick = onDiscard, modifier = wide) { Text("Done") }
                // Discard is the filled button on a suspect message, so the
                // default reach of a thumb is the safe one.
                found.warning != null -> {
                    Button(onClick = onDiscard, modifier = wide) { Text("Discard") }
                    OutlinedButton(onClick = save, modifier = wide) { Text("Save anyway") }
                }
                else -> {
                    // Never disabled: a refusal with no reason attached is
                    // worse than the message under the amount saying why.
                    Button(onClick = save, modifier = wide) {
                        Text(if (commit == null) "Save" else "Save ${Insights.money(commit.amount)}")
                    }
                    OutlinedButton(onClick = onDiscard, modifier = wide) { Text("Discard") }
                }
            }
        }
    }

    if (showDatePicker) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // UTC midnight of the day tapped; read back in the
                        // device zone it lands a day early west of Greenwich.
                        picker.selectedDateMillis?.let {
                            date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        showDatePicker = false
                    },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Set date") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDatePicker = false },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Cancel") }
            },
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun NotATransactionCard(reason: String, logged: Boolean, onClear: () -> Unit) {
    PasteCard {
        Text(
            "That doesn't read as a bank transaction",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(6.dp))
        Hint(reason)
        Spacer(Modifier.height(4.dp))
        Hint("Nothing was saved.")
        if (logged) {
            Spacer(Modifier.height(4.dp))
            Hint("A copy with every number and name masked is in Settings, if you want " +
                 "to send it on.")
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = onClear,
            modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
        ) { Text("Clear") }
    }
}

/**
 * How the row will be filed, from the commit Save would write. Null while the
 * amount is not one Save would accept; the message under the field says why.
 */
private fun statusLine(found: Paste.Preview.Transaction, commit: Paste.Commit?): String? {
    val c = commit ?: return null
    val filing = when {
        c.learn != null ->
            "You named the payee, so it's filed as confirmed and SpendWise learns it."
        found.warning != null -> "Saving it says it's real, so it's filed as confirmed."
        c.status == Pipeline.STATUS_CONFIRMED ->
            "SpendWise knows this payee (${c.confidence}% sure)."
        c.status == Pipeline.STATUS_PENDING ->
            "SpendWise isn't sure of the payee (${c.confidence}% sure), so it will ask you " +
            "to confirm it. Fix the name or pick a category to confirm it now."
        else -> "First time seeing this payee, so it's saved for review. Name it or pick " +
                "a category to confirm it now."
    }
    return if (c.categoryId == null) "$filing It has no category yet, so it will wait in " +
                                     "Review for one."
           else filing
}

/** The chips [CategoryPicker] shows for [type], by the same rule: one side of
 *  the ledger, or everything when that side has none. */
private fun offeredFor(all: List<CategoryEntity>, type: String): List<CategoryEntity> =
    all.filter { it.type == type }.ifEmpty { all }

/** Grouping commas and a pasted rupee sign are ordinary input, not errors. */
private fun typedAmount(raw: String): Double? =
    raw.replace(",", "").replace(" ", "").replace("₹", "")
        .toDoubleOrNull()?.takeIf { it.isFinite() }

/** A field the user may be about to edit should not open on "450.0". */
private fun editableAmount(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.2f", value)

@Composable
private fun PasteCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
