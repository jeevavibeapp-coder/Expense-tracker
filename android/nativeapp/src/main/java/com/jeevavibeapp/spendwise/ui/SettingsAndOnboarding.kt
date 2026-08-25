package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Insights
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings, privacy, help, and the first-run introduction.
 *
 * These four screens are the ones that have to be *true*. Everywhere else a
 * wrong word costs a moment of confusion; here it costs the user's trust in
 * an app that reads their bank messages. Two rules follow from that, and both
 * are load-bearing:
 *
 *  - Say what is never read, not only what is. "Never personal messages,
 *    never OTPs" is the sentence people are actually looking for, and it is
 *    on screen before the permission is asked for rather than after.
 *  - Never offer a control that cannot work. An "Allow SMS access" button in
 *    the no-SMS flavour asks for a permission this APK does not declare: the
 *    request is denied instantly by the system, and the user is left tapping
 *    a button that will never do anything.
 */

/**
 * Three genuinely different situations, not two.
 *
 * [UNAVAILABLE] is the no-SMS flavour, where the permission is not in the
 * manifest at all. Collapsing it into [DENIED] would put an Allow button in
 * front of a user who has nothing to allow.
 */
enum class SmsAccess { GRANTED, DENIED, UNAVAILABLE }

/** Everything the settings and help screens need to say about capture. */
data class SmsStatus(
    val access: SmsAccess,
    val captured: Int = 0,
    val queued: Int = 0,
    /** Already formatted for display; these screens do no date arithmetic. */
    val lastCapture: String? = null,
    val unreadableKinds: Int = 0,
    val unreadableTotal: Int = 0,
    val unreviewed: Int = 0,
)

data class AppPrefs(
    val currency: String = "INR",
    val theme: String = THEME_SYSTEM,
    val autoSaveThreshold: Int = 85,
    val confirmThreshold: Int = 55,
    val highValueAmount: Double? = null,
)

const val THEME_SYSTEM = "system"
const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"

// ── Welcome ─────────────────────────────────────────────────────────────

/**
 * One scrollable screen rather than a swipeable carousel. A carousel lets
 * people flick past the only page that explains what will be read from their
 * phone, and it is the hardest pattern on this screen to use with TalkBack.
 */
@Composable
fun WelcomeScreen(
    sms: SmsStatus,
    onAllowSms: () -> Unit,
    onPrivacy: () -> Unit,
    onDone: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 32.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item {
            PageCard {
                Text("SPENDWISE", style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Your money, tracked on your phone.",
                    style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(10.dp))
                Text("No account. No cloud. No ads. Nothing about your spending is " +
                     "sent anywhere, because there is nowhere for it to go.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { GroupHeader("How it works") }
        item {
            PageCard {
                HairlineList(
                    "It reads your bank SMS" to
                        "Bank and UPI messages already arrive on your phone. SpendWise " +
                        "reads the amount, the merchant and the date out of them, so you " +
                        "do not type anything in.",
                    "It learns your merchants" to
                        "Correct a category once and it remembers. The learning happens " +
                        "on this device, from your corrections only.",
                    "It flags what looks wrong" to
                        "Messages from senders your bank never uses, or with the shape of " +
                        "a scam, are held aside for you instead of being added silently.",
                )
            }
        }

        // Before the permission card, never after it. Someone deciding whether
        // to grant SMS access needs the "never" list at the moment they
        // decide, not on a screen they can reach afterwards.
        item { GroupHeader("What SpendWise reads") }
        item {
            PageCard {
                Note("Being specific here matters more than being reassuring, so here " +
                     "is exactly what the SMS permission is used for.")
                Spacer(Modifier.height(12.dp))
                Mark(true, "Messages from bank and UPI sender IDs — the amount, " +
                    "merchant, date and reference number in them.")
                Spacer(Modifier.height(8.dp))
                Mark(true, "Kept in a database on this phone, which only this app can open.")
                Spacer(Modifier.height(8.dp))
                Mark(false, "Never messages from people. Never OTPs.")
                Spacer(Modifier.height(8.dp))
                Mark(false, "Never uploaded, never analysed off the device, never " +
                    "shared — there is no server and no analytics in this app at all.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onPrivacy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Read the full privacy note") }
            }
        }

        when (sms.access) {
            SmsAccess.UNAVAILABLE -> item {
                PageCard {
                    Text("About this build", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Note("This copy was built without SMS access, so nothing is read " +
                         "from your messages at all. Add transactions yourself, or paste " +
                         "a bank message in and SpendWise will read the amount out of it.")
                }
            }
            // Nothing is granted yet on a genuine first run, so this is the
            // ordinary path. It is skipped once the permission is held —
            // asking again for something already given reads as a bug.
            SmsAccess.DENIED -> item {
                PageCard {
                    Text("Turn on SMS capture", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Note("Without it SpendWise still works — you can add transactions " +
                         "yourself or paste a message in. With it, the ledger fills itself.")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onAllowSms,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                    ) { Text("Allow SMS access") }
                }
            }
            SmsAccess.GRANTED -> Unit
        }

        item {
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) { Text("Start using SpendWise") }
        }
        item { Footnote("You can read this again any time from Settings.") }
    }
}

// ── Settings ────────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    sms: SmsStatus,
    onSavePrefs: (AppPrefs) -> Unit,
    onAllowSms: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onExportCsv: () -> Unit,
    onExportUnreadable: () -> Unit,
    onClearUnreviewed: () -> Unit,
    onPrivacy: () -> Unit,
    onHelp: () -> Unit,
    onHowItWorks: () -> Unit,
    onImport: (() -> Unit)? = null,
    flash: String? = null,
) {
    // Re-seeded whenever the stored preferences change, so the fields show
    // what is saved rather than what was last typed into them.
    var draft by remember(prefs) { mutableStateOf(prefs) }
    var amountText by remember(prefs) { mutableStateOf(amountField(prefs.highValueAmount)) }
    var amountRejected by remember(prefs) { mutableStateOf(false) }
    var confirmingPurge by remember { mutableStateOf(false) }

    val savedAmountText = amountField(prefs.highValueAmount)
    val dirty = draft != prefs || amountText != savedAmountText

    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        flash?.let { item { PageCard { Text(it, style = MaterialTheme.typography.bodyMedium) } } }

        item { GroupHeader("Preferences") }
        item {
            PageCard {
                Text("Currency", style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft.currency,
                    onValueChange = { draft = draft.copy(currency = it.take(8)) },
                    singleLine = true,
                    placeholder = { Text("INR") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                )
                Spacer(Modifier.height(16.dp))
                Text("Appearance", style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                ThemeChoice(draft.theme) { draft = draft.copy(theme = it) }
            }
        }

        item {
            PageCard {
                Text("Smart engine", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Note("How confident the engine must be to auto-save vs ask you.")
                Spacer(Modifier.height(14.dp))
                ThresholdSlider(
                    label = "Auto-save at ${draft.autoSaveThreshold}% confidence",
                    value = draft.autoSaveThreshold,
                    tint = Income,
                ) { draft = draft.copy(autoSaveThreshold = it) }
                Spacer(Modifier.height(14.dp))
                ThresholdSlider(
                    label = "Ask to confirm above ${draft.confirmThreshold}%",
                    value = draft.confirmThreshold,
                    tint = Warn,
                ) { draft = draft.copy(confirmThreshold = it) }
            }
        }

        item {
            PageCard {
                Text("Fraud protection", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it; amountRejected = false },
                    label = { Text("High-value alert amount (optional)") },
                    placeholder = { Text("e.g. 25000") },
                    singleLine = true,
                    isError = amountRejected,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                )
                Spacer(Modifier.height(6.dp))
                val parsed = positiveAmount(amountText)
                when {
                    amountRejected -> Text(
                        "Enter an amount above zero, or leave it empty for no alert.",
                        style = MaterialTheme.typography.bodySmall, color = Expense)
                    parsed != null -> Note(
                        "Transactions at ${Insights.money(parsed)} or above raise an alert.")
                    else -> Note("Transactions at or above this raise an alert.")
                }
            }
        }

        item {
            Button(
                onClick = {
                    val amount = positiveAmount(amountText)
                    if (amountText.isNotBlank() && amount == null) amountRejected = true
                    else onSavePrefs(draft.copy(highValueAmount = amount))
                },
                enabled = dirty,
                modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            ) { Text("Save changes") }
        }

        item { GroupHeader("SMS capture") }
        item {
            PageCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Status", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    StatusPill(sms)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCell("CAPTURED", sms.captured.toString(), Modifier.weight(1f))
                    StatCell("IN QUEUE", sms.queued.toString(), Modifier.weight(1f))
                    StatCell("LAST", sms.lastCapture ?: "–", Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))

                when {
                    sms.access == SmsAccess.UNAVAILABLE -> {
                        // The no-SMS flavour. An Allow button here would ask
                        // the user to grant something this APK never requests.
                        Note("This copy of SpendWise was built without SMS access, so " +
                             "bank messages can't be captured automatically. Everything " +
                             "else works — add a transaction yourself, or paste a bank " +
                             "message in.")
                        onImport?.let {
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = it,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = Tokens.minTouchTarget),
                            ) { Text("Paste a bank message") }
                        }
                    }
                    sms.access == SmsAccess.DENIED -> {
                        Note("SpendWise can't read bank messages. Tap Allow to turn " +
                             "capture back on.")
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onAllowSms,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = Tokens.minTouchTarget),
                        ) { Text("Allow SMS access") }
                    }
                    sms.captured == 0 -> Note(
                        "Nothing captured yet. SpendWise scans your inbox for bank and " +
                        "UPI messages each time it opens. If this stays at 0, your phone " +
                        "may be blocking background messages — on Xiaomi, Redmi and POCO, " +
                        "Oppo, Vivo or Realme, enable Autostart for SpendWise in " +
                        "Settings → Apps, then reopen the app.")
                    else -> Note(
                        "Bank and UPI messages are captured automatically and parsed on " +
                        "this device — they never leave your phone.")
                }

                if (sms.unreadableKinds > 0) {
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(14.dp))
                    Text("${sms.unreadableKinds} message " +
                         plural(sms.unreadableKinds, "type", "types") + " not understood",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Note("${sms.unreadableTotal} bank " +
                         plural(sms.unreadableTotal, "message", "messages") +
                         " looked financial but could not be read — usually a format " +
                         "this build doesn't know yet. They stay on your device.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onExportUnreadable,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                    ) { Text("Export unreadable messages") }
                }

                if (sms.unreviewed > 0) {
                    Spacer(Modifier.height(14.dp))
                    TextButton(
                        onClick = { confirmingPurge = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = Expense),
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                    ) {
                        Text("Clear ${sms.unreviewed} unreviewed " +
                             plural(sms.unreviewed, "capture", "captures"))
                    }
                    Footnote("Use this to wipe junk captured before filtering improved. " +
                             "Anything you already confirmed or categorised is kept.")
                }
            }
        }

        // Backup first, because it is the one thing in this app that no other
        // screen can undo: there is no cloud copy, so a phone lost without a
        // backup is a ledger lost for good.
        item { GroupHeader("Your data") }
        item {
            PageCard {
                Note("Everything stays on this phone, which means the only copy is the " +
                     "one you make.")
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Download a full backup") }
                Spacer(Modifier.height(4.dp))
                NavRow("Restore from a backup",
                    "Moving to a new phone, or undoing a mistake", onRestore)
                NavRow("Export as CSV",
                    "A report for a spreadsheet — not a backup", onExportCsv)
            }
        }

        item { GroupHeader("About") }
        item {
            PageCard {
                NavRow("Privacy", "What is stored, and what is never done", onPrivacy)
                NavRow("Help & troubleshooting",
                    "Captures not arriving, duplicates, new phone", onHelp)
                NavRow("How SpendWise works", "The introduction, again", onHowItWorks)
            }
        }

        item { Footnote("SpendWise · on-device & offline") }
    }

    if (confirmingPurge) {
        AlertDialog(
            onDismissRequest = { confirmingPurge = false },
            title = { Text("Clear unreviewed captures?") },
            text = {
                Text("This removes ${sms.unreviewed} SMS " +
                     plural(sms.unreviewed, "capture", "captures") +
                     " you have not reviewed. Anything you already confirmed or " +
                     "categorised is kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingPurge = false; onClearUnreviewed() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Expense),
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Clear them") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmingPurge = false },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Keep them") }
            },
        )
    }
}

// ── Privacy ─────────────────────────────────────────────────────────────

/**
 * Written in the second person and in plain sentences on purpose. A privacy
 * policy nobody reads protects the author, not the user.
 */
@Composable
fun PrivacyScreen(onBackup: () -> Unit, onExportCsv: () -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 32.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item {
            PageCard {
                Text("The short version", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Everything stays on this phone. SpendWise has no account, no " +
                     "server and no analytics. It cannot send your spending anywhere " +
                     "because there is nowhere for it to be sent.",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }

        item { GroupHeader("What is stored") }
        item {
            PageCard {
                HairlineList(
                    "Your transactions" to
                        "Amount, merchant, category, date, and the bank message a " +
                        "transaction came from. In a SQLite file in this app's private " +
                        "storage, which other apps on the phone cannot read.",
                    "What you have corrected" to
                        "When you recategorise a merchant, that correction is stored so " +
                        "the next one is right. It is used only to categorise your own " +
                        "transactions.",
                    "Sender reputations" to
                        "Which sender IDs your bank actually uses, learned from the " +
                        "messages you have received, so a lookalike can be spotted.",
                    "Your settings" to "Currency, theme, thresholds.",
                )
            }
        }

        item { GroupHeader("What is never done") }
        item {
            PageCard {
                // The Python build ran a web server on a loopback port and had
                // to explain it away here. There is no server now — not even a
                // local one — which is a shorter and much stronger sentence.
                Mark(false, "No network requests at all. There is no server to reach, " +
                    "on this phone or anywhere else.")
                Spacer(Modifier.height(8.dp))
                Mark(false, "No analytics, no crash reporting, no advertising " +
                    "identifiers, no third-party SDKs.")
                Spacer(Modifier.height(8.dp))
                Mark(false, "No account, no email address, no phone number.")
                Spacer(Modifier.height(8.dp))
                Mark(false, "Nothing is read from messages that are not from a bank or " +
                    "UPI sender — personal messages and OTPs are ignored.")
            }
        }

        item { GroupHeader("Your copy of everything") }
        item {
            PageCard {
                Note("Because there is no cloud copy, keeping a backup is on you — and " +
                     "it should be, but the app has to make it easy.")
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onBackup,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Download a full backup") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onExportCsv,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Export transactions as CSV") }
            }
        }

        item { GroupHeader("Deleting your data") }
        item {
            PageCard {
                Text("Uninstalling SpendWise deletes the database with it. There is no " +
                     "copy anywhere else, so an uninstall is permanent — take a backup " +
                     "first if you want to keep the history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text("To clear only what SMS capture picked up, use “Clear unreviewed " +
                     "captures” in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { Footnote("SpendWise · offline by design") }
    }
}

// ── Help ────────────────────────────────────────────────────────────────

/**
 * Ordered by how often each problem happens, not by topic. The first item is
 * the one that brings people here.
 *
 * The old first entry — "Couldn't start SpendWise" — is gone, along with the
 * embedded interpreter, the loopback port and the "Address already in use"
 * failure it existed to explain. A native app has no engine to fail to start,
 * and keeping the answer would be describing a symptom this build cannot
 * produce.
 */
@Composable
fun HelpScreen(
    sms: SmsStatus,
    onAllowSms: () -> Unit,
    onExportUnreadable: () -> Unit,
    onImport: (() -> Unit)? = null,
) {
    // Only one open at a time. Several expanded answers turn the list into a
    // wall the user has to scroll past to reach the next question.
    var open by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 32.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item { Footnote("Answers to what actually goes wrong") }

        when (sms.access) {
            SmsAccess.UNAVAILABLE -> item {
                PageCard {
                    Text("This build has no SMS access",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Note("Nothing is read from your messages. Paste a bank message in, " +
                         "or add transactions by hand. If you want automatic capture, " +
                         "install the full build — see the install question below.")
                    onImport?.let {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = it,
                            modifier = Modifier.fillMaxWidth()
                                .heightIn(min = Tokens.minTouchTarget),
                        ) { Text("Paste a bank message") }
                    }
                }
            }
            SmsAccess.DENIED -> item {
                PageCard {
                    Text("SMS access is off", style = MaterialTheme.typography.titleMedium,
                        color = Expense)
                    Spacer(Modifier.height(6.dp))
                    Note("Nothing can be captured automatically until it is on.")
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onAllowSms,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                    ) { Text("Allow SMS access") }
                }
            }
            SmsAccess.GRANTED -> Unit
        }

        items@ for (topic in HELP_TOPICS) {
            item(key = topic.question) {
                PageCard {
                    HelpEntry(
                        topic = topic,
                        expanded = open == topic.question,
                        onToggle = { open = if (open == topic.question) null else topic.question },
                    )
                }
            }
        }

        item {
            PageCard {
                Text("Still stuck?", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Note("Nothing is sent from this app automatically. If you want to " +
                     "report a message your bank sends that SpendWise cannot read, " +
                     "exporting it is an explicit action you take, and the file stays " +
                     "on your phone until you choose to share it.")
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onExportUnreadable,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text("Export unreadable messages") }
            }
        }
    }
}

@Composable
private fun HelpEntry(topic: HelpTopic, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) "Collapse answer" else "Expand answer",
                onClick = onToggle,
            )
            // Real expand/collapse, not a chevron that only looks like one:
            // TalkBack announces the state and offers the matching action.
            .semantics(mergeDescendants = true) {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
                if (expanded) collapse { onToggle(); true } else expand { onToggle(); true }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(topic.question, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(if (expanded) "−" else "+", style = MoneyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            for (block in topic.body) {
                when (block) {
                    is Para -> Text(block.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is Steps -> block.items.forEachIndexed { i, line ->
                        Row(Modifier.padding(top = if (i == 0) 0.dp else 6.dp)) {
                            Text(if (block.ordered) "${i + 1}." else "·",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(20.dp))
                            Text(line, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private sealed interface HelpBlock
private data class Para(val text: String) : HelpBlock
private data class Steps(val items: List<String>, val ordered: Boolean = true) : HelpBlock
private data class HelpTopic(val question: String, val body: List<HelpBlock>)

private val HELP_TOPICS: List<HelpTopic> = listOf(
    HelpTopic("Android refused to install the app", listOf(
        Para("Two different refusals, with two different causes."),
        Para("“App blocked to protect your device” (Play Protect), or " +
             "INSTALL_FAILED_VERIFICATION_FAILURE. SpendWise asks to read bank SMS, " +
             "and Play Protect blocks any app that requests SMS access unless it came " +
             "from the Play Store. It is not reacting to anything found in the app — it " +
             "is reacting to the permission being requested at all."),
        Steps(listOf(
            "Play Store → your profile picture → Play Protect",
            "The gear icon → turn off Scan apps with Play Protect",
            "Install SpendWise",
            "Turn it back on. It protects you from everything else.",
        )),
        Para("On Xiaomi, Redmi and POCO there is a second gate: Settings → Privacy " +
             "protection → Special permissions → Install unknown apps, and if it still " +
             "refuses, turn off MIUI optimization in Developer options."),
        Para("From a computer, adb install app-release.apk avoids the prompt entirely."),
        Para("In India, Singapore, Thailand and Brazil this is Google's enhanced fraud " +
             "protection, which refuses any sideloaded app declaring SMS, " +
             "notification-listener or accessibility permissions — usually with no " +
             "“install anyway”. Turning Play Protect off does not always help there."),
        Para("Two things that do work:"),
        Steps(listOf(
            "adb install from a computer. The ADB route is not subject to this check.",
            "Install the no-SMS build. It never asks for the permission, so there is " +
                "nothing to block. You add transactions yourself or paste bank " +
                "messages in.",
        )),
        Para("Apps like this one on the Play Store are not built differently — they " +
             "arrive by a route that is not scanned."),
        Para("INSTALL_PARSE_FAILED_NO_CERTIFICATES is a different thing: that APK was " +
             "never signed and no setting will let it install. Use a signed build."),
    )),

    HelpTopic("My transactions are not being captured", listOf(
        Para("SpendWise scans the inbox for bank and UPI messages each time it opens, " +
             "and listens for new ones while it runs."),
        Para("If nothing is being captured, in order of likelihood:"),
        Steps(listOf(
            "SMS permission is off. Settings → SMS capture will say so, with a button " +
                "to turn it back on.",
            "Your phone is killing the app in the background. On Xiaomi, Redmi and " +
                "POCO, Oppo, Vivo and Realme this is the usual cause. Enable Autostart " +
                "for SpendWise in your phone's Settings → Apps, and set its battery " +
                "mode to No restrictions.",
            "Your bank's message format is not recognised yet. Settings shows a count " +
                "of messages that looked financial but could not be read; you can " +
                "export them from there to see what was missed.",
        )),
    )),

    HelpTopic("A message was held instead of being added", listOf(
        Para("That happens when the sender is not one your bank has used before, or " +
             "the message has the shape of a scam — a payment link, an urgent deadline, " +
             "a request to call a number."),
        Para("Held messages are never deleted and never added on their own. Open " +
             "Alerts to see each one with the reason, the original text, and buttons to " +
             "add it or discard it."),
    )),

    HelpTopic("A transaction got the wrong category", listOf(
        Para("Tap the transaction and change it. That correction is remembered for that " +
             "merchant, and the next one arrives already correct."),
        Para("How eager the app is to categorise without asking is yours to set: " +
             "Settings → Smart engine has the two confidence thresholds. Raise " +
             "auto-save to be asked more often; lower it to be asked less."),
    )),

    HelpTopic("The same purchase appears twice", listOf(
        Para("Some banks send both an SMS and a UPI confirmation for one payment. " +
             "SpendWise removes exact duplicates automatically, but a pair that differs " +
             "in wording or timing can slip through."),
        Para("Open the row and delete it. Deletes are soft — an undo appears, and " +
             "nothing is really gone until the app is reinstalled."),
    )),

    HelpTopic("I am getting a new phone", listOf(
        Para("Nothing is stored anywhere except this phone, so a copy has to be made " +
             "deliberately."),
        Steps(listOf(
            "Settings → Download a full backup. Save the file somewhere off the phone.",
            "Install SpendWise on the new phone.",
            "Settings → Restore from a backup, and pick that file.",
        )),
        Para("The CSV export is a report for a spreadsheet, not a backup — it cannot " +
             "rebuild your merchants, budgets or learned categories."),
    )),

    HelpTopic("The numbers do not look right", listOf(
        Para("Two corrections are applied automatically and explain most surprises:"),
        Steps(listOf(
            "A transfer between your own accounts is not spending, so a matching debit " +
                "and credit within a few minutes is excluded from totals.",
            "A refund reduces the original spending instead of counting as income.",
        ), ordered = false),
        Para("If a total still looks wrong, searching Activity for the merchant is the " +
             "fastest way to find what is behind it."),
    )),

    HelpTopic("Does this work without internet?", listOf(
        Para("Yes, always — that is the whole design. There is no server to reach, so " +
             "aeroplane mode changes nothing about how the app behaves."),
    )),
)

// ── Shared pieces ───────────────────────────────────────────────────────

@Composable
private fun PageCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text.uppercase(),
        style = SectionHeading,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Footnote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
}

/** A ✓/✗ line. The mark carries the meaning, so it is the only thing here
 *  allowed a colour of its own. */
@Composable
private fun Mark(included: Boolean, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(if (included) "✓" else "✗",
            style = MaterialTheme.typography.bodyMedium,
            color = if (included) Income else Expense,
            modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Title-and-explanation rows in one card separated by hairlines, rather than
 *  one card each: the same pattern the dashboard uses for its insights. */
@Composable
private fun HairlineList(vararg rows: Pair<String, String>) {
    rows.forEachIndexed { i, (title, detail) ->
        if (i > 0) HorizontalDivider(
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(vertical = 14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Tokens.minTouchTarget)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text("›", style = MoneyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusPill(sms: SmsStatus) {
    val (label, tint) = when {
        sms.access == SmsAccess.UNAVAILABLE ->
            "Not in this build" to MaterialTheme.colorScheme.onSurfaceVariant
        sms.access == SmsAccess.DENIED -> "Permission off" to Warn
        sms.captured > 0 -> "Working" to Income
        else -> "No captures yet" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(999.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(label.uppercase(), style = MicroLabel, color = tint,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
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
            Text(value, style = MoneyMedium, maxLines = 1)
        }
    }
}

@Composable
private fun ThemeChoice(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(THEME_SYSTEM to "System", THEME_LIGHT to "Light", THEME_DARK to "Dark")
    Row(Modifier.fillMaxWidth().selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                Modifier.weight(1f)
                    .heightIn(min = Tokens.minTouchTarget)
                    .selectable(
                        selected = selected == value,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onClick = null: the whole row is the target, and a second
                // clickable inside it would halve the touch area.
                RadioButton(selected = selected == value, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ThresholdSlider(label: String, value: Int, tint: Color, onValue: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium, color = tint)
    Slider(
        value = value.toFloat(),
        onValueChange = { onValue(it.roundToInt()) },
        valueRange = 0f..100f,
        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
    )
}

private fun plural(count: Int, one: String, many: String): String =
    if (count == 1) one else many

/** What goes in the field: a plain number, not a formatted amount. Grouping
 *  separators and a currency symbol are things the user would have to delete
 *  before they could type. */
private fun amountField(value: Double?): String = when {
    value == null -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> String.format(Locale.US, "%.2f", value)
}

private fun positiveAmount(raw: String): Double? =
    raw.filter { it.isDigit() || it == '.' }
        .toDoubleOrNull()
        ?.takeIf { it > 0 && it.isFinite() }
