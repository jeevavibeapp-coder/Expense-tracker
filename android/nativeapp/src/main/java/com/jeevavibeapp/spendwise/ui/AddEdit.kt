package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.data.CategoryEntity
import com.jeevavibeapp.spendwise.data.Repo
import com.jeevavibeapp.spendwise.data.TransactionEntity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Adding and editing a transaction — one sheet for both.
 *
 * The sheet owns no data of its own. Merchant resolution arrives as a
 * suspend lambda and persistence as callbacks, so the thing that decides what
 * a payee is called stays in the repository (and the rules behind it stay in
 * :core), and this file stays a form.
 */

private const val RESOLVE_DEBOUNCE_MS = 250L

/** Roughly one sheet animation. */
private const val FOCUS_SETTLE_MS = 220L

/** Long enough to notice the deletion and reach the button, short enough that
 *  the sheet does not sit open on a transaction that is already gone. */
private const val UNDO_WINDOW_MS = 6_000L

private val DateLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

/**
 * @param categories offered by the picker; only id, name and type are read.
 * @param resolveMerchant what the engine would call this payee — normally
 *   `{ repo.resolveMerchant(it, null, null).merchantName }`. Called on a
 *   background-friendly coroutine and cancelled on every keystroke.
 * @param onSave persist the row. The sheet calls [onDismiss] straight after,
 *   so the host needs only one closing path.
 * @param onDelete soft-delete the row. The host must NOT close the sheet from
 *   here — the sheet stays up to offer the undo.
 * @param onRestore clear the soft delete. [onDelete] and [onRestore] are a
 *   pair: the delete control appears only when editing and both are supplied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditSheet(
    categories: List<CategoryEntity>,
    resolveMerchant: suspend (String) -> String?,
    onSave: (TransactionEntity) -> Unit,
    onDismiss: () -> Unit,
    existing: TransactionEntity? = null,
    onDelete: ((TransactionEntity) -> Unit)? = null,
    onRestore: ((TransactionEntity) -> Unit)? = null,
) {
    // A form whose amount field starts below the fold cannot be focused into
    // usefully, and this one is taller than a partial sheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Keyed on the row being edited so reusing the sheet for a different
    // transaction starts from that transaction rather than the last one's
    // half-typed state.
    val key = existing?.id
    var amount by remember(key) { mutableStateOf(existing?.let { editable(it.amount) } ?: "") }
    var type by remember(key) { mutableStateOf(existing?.type ?: "expense") }
    var merchant by remember(key) {
        mutableStateOf(existing?.merchantName ?: existing?.rawMerchant ?: "")
    }
    var categoryId by remember(key) { mutableStateOf(existing?.categoryId) }
    var date by remember(key) {
        mutableStateOf(existing?.let { Repo.localTime(it.occurredAt).toLocalDate() }
            ?: LocalDate.now())
    }
    var notes by remember(key) { mutableStateOf(existing?.notes ?: "") }

    // Only the date is editable, so the original time of day is carried
    // through untouched — it is what the hour histogram behind merchant
    // scoring is built from.
    val timeOfDay = remember(key) {
        existing?.let { Repo.localTime(it.occurredAt).toLocalTime() } ?: LocalTime.now()
    }

    var attempted by remember(key) { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var pendingUndo by remember(key) { mutableStateOf<TransactionEntity?>(null) }

    val typed = typedAmount(amount)
    val amountValue = typed?.takeIf { it > 0 }
    val amountError = if (!attempted || amountValue != null) null else when {
        amount.isBlank() -> "Enter an amount"
        typed == null -> "Amounts look like 249 or 249.50"
        else -> "Amount has to be more than zero"
    }

    val payee = merchant.trim()

    // The answer is kept next to the question it answers. Without that pairing
    // a resolution for "swig" is still on screen — and still what Save would
    // store — while the field already reads "ZOMATO".
    var resolution by remember(key) { mutableStateOf<Pair<String, String?>?>(null) }
    LaunchedEffect(payee) {
        if (payee.isEmpty()) {
            resolution = null
            return@LaunchedEffect
        }
        // The lookup reads the learning table; debouncing keeps it off every
        // keystroke, and LaunchedEffect cancels the in-flight one for free.
        delay(RESOLVE_DEBOUNCE_MS)
        resolution = payee to resolveMerchant(payee)
    }
    val resolvedName = resolution?.takeIf { it.first == payee }?.second
        ?.takeIf { it.isNotBlank() && !it.equals(payee, ignoreCase = true) }

    LaunchedEffect(pendingUndo) {
        if (pendingUndo == null) return@LaunchedEffect
        delay(UNDO_WINDOW_MS)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(Tokens.screenPadding, 0.dp, Tokens.screenPadding, 28.dp),
        ) {
            val deleted = pendingUndo
            if (deleted != null) {
                UndoPanel(
                    name = deleted.merchantName ?: deleted.rawMerchant ?: "Transaction",
                    amount = deleted.amount,
                    onUndo = {
                        onRestore?.invoke(deleted)
                        pendingUndo = null
                    },
                    onDone = onDismiss,
                )
                return@Column
            }

            Text(
                if (existing == null) "Add transaction" else "Edit transaction",
                style = MaterialTheme.typography.headlineLarge,
            )
            Spacer(Modifier.height(18.dp))

            AmountField(
                value = amount,
                onValue = { amount = it },
                error = amountError,
            )

            Spacer(Modifier.height(14.dp))
            TypeToggle(type = type, onType = { picked ->
                type = picked
                // A category belongs to one side of the ledger. Leaving Food
                // selected while the row turns into income files a salary
                // under groceries, and the chip is not even on screen to
                // notice it.
                if (offered(categories, picked).none { it.id == categoryId }) categoryId = null
            })

            Spacer(Modifier.height(14.dp))
            // Says exactly what will be stored, before it is stored.
            val merchantHint: (@Composable () -> Unit)? = resolvedName?.let { name ->
                { Text("Saved as $name") }
            }
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("Merchant") },
                singleLine = true,
                supportingText = merchantHint,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            )

            Spacer(Modifier.height(18.dp))
            Text(
                "CATEGORY", style = MicroLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            CategoryPicker(
                categories = categories,
                selectedId = categoryId,
                onSelect = { categoryId = it },
                type = type,
            )

            Spacer(Modifier.height(18.dp))
            Text("DATE", style = MicroLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) { Text(date.format(DateLabel)) }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            )

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    attempted = true
                    if (amountValue == null) return@Button
                    onSave(compose(
                        existing = existing,
                        amount = amountValue,
                        type = type,
                        merchant = merchant,
                        resolvedName = resolvedName,
                        categoryId = categoryId,
                        occurredAt = Repo.millis(date.atTime(timeOfDay)),
                        notes = notes,
                    ))
                    onDismiss()
                },
                // Deliberately never disabled: a greyed-out button is a
                // refusal with no reason attached, and the reason is the whole
                // point of the message under the field.
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) {
                Text(if (amountValue == null) "Save"
                     else "Save ${Insights.money(amountValue)}")
            }

            if (existing != null && onDelete != null && onRestore != null) {
                Spacer(Modifier.height(6.dp))
                TextButton(
                    onClick = {
                        // Deleted for real, immediately. The schema soft-deletes,
                        // so the undo below restores the same row rather than
                        // rebuilding a copy of it — which is why this needs no
                        // "are you sure".
                        onDelete(existing)
                        pendingUndo = existing
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
                        // The picker's millis are UTC midnight for the day the
                        // user tapped. Reading them back in the device zone
                        // lands on the day before for any negative offset.
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

/**
 * The categories offered for [type], as chips.
 *
 * Uncategorised is offered as a choice rather than left as the absence of
 * one: it is a real answer, and a picker with no way back out of it teaches
 * people to pick something wrong instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    type: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Uncategorised") },
            modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
        )
        offered(categories, type).forEach { c ->
            FilterChip(
                selected = c.id == selectedId,
                onClick = { onSelect(c.id) },
                label = { Text(c.name) },
                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
            )
        }
    }
}

@Composable
private fun AmountField(value: String, onValue: (String) -> Unit, error: String?) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // The sheet animates in; asking for focus before it is attached is
        // either ignored or throws, so this waits for it to settle.
        delay(FOCUS_SETTLE_MS)
        runCatching { focus.requestFocus() }
    }
    val message: (@Composable () -> Unit)? = error?.let { text -> { Text(text) } }
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // Letters cannot be part of an amount, and rejecting them as they
            // are typed is kinder than complaining about them at save time.
            if (input.all { it.isDigit() || it == '.' || it == ',' }) onValue(input)
        },
        label = { Text("Amount") },
        leadingIcon = { Text("₹") },
        singleLine = true,
        isError = error != null,
        supportingText = message,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        textStyle = MoneyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.minTouchTarget)
            .focusRequester(focus),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeToggle(type: String, onType: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = type == "expense",
            onClick = { onType("expense") },
            label = { Text("Expense") },
            modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
        )
        FilterChip(
            selected = type == "income",
            onClick = { onType("income") },
            label = { Text("Income") },
            modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
        )
    }
}

@Composable
private fun UndoPanel(name: String, amount: Double, onUndo: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text("Deleted", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "$name · ${Insights.money(amount)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onUndo,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
            ) { Text("Undo") }
            OutlinedButton(
                onClick = onDone,
                modifier = Modifier.weight(1f).heightIn(min = Tokens.minTouchTarget),
            ) { Text("Done") }
        }
    }
}

/**
 * The categories a picker set to [type] actually shows.
 *
 * Falling back to everything matters more than the filter does: a ledger
 * whose categories are all seeded as "expense" would otherwise show an empty
 * picker the moment someone records income.
 */
private fun offered(all: List<CategoryEntity>, type: String?): List<CategoryEntity> {
    val matching = all.filter { type == null || it.type == type }
    return if (matching.isEmpty()) all else matching
}

/** What the user typed, as a number, or null if it is not one. Grouping
 *  commas and a pasted rupee sign are ordinary input, not errors. */
private fun typedAmount(raw: String): Double? =
    raw.replace(",", "").replace(" ", "").replace("₹", "")
        .toDoubleOrNull()?.takeIf { it.isFinite() }

/** Trailing ".0" in a field the user is about to edit reads as noise. */
private fun editable(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

private fun compose(
    existing: TransactionEntity?,
    amount: Double,
    type: String,
    merchant: String,
    resolvedName: String?,
    categoryId: String?,
    occurredAt: Long,
    notes: String,
): TransactionEntity {
    val typedName = merchant.trim().ifBlank { null }
    val display = resolvedName ?: typedName
    val cleanNotes = notes.trim().ifBlank { null }

    // An edit is a copy: dedupKey, smsSender, source, confidence and the
    // original raw payee all survive, so correcting a name never detaches the
    // row from the bank message it came from or lets that message back in.
    return existing?.copy(
        amount = amount,
        type = type,
        categoryId = categoryId,
        merchantName = display,
        notes = cleanNotes,
        occurredAt = occurredAt,
    ) ?: TransactionEntity(
        id = Repo.newId(),
        amount = amount,
        type = type,
        categoryId = categoryId,
        rawMerchant = typedName,
        merchantName = display,
        notes = cleanNotes,
        occurredAt = occurredAt,
        source = "manual",
        status = "confirmed",
        // The picker was on screen, so the category question has been asked
        // and should not be asked again later.
        categoryPrompted = true,
        createdAt = System.currentTimeMillis(),
    )
}
