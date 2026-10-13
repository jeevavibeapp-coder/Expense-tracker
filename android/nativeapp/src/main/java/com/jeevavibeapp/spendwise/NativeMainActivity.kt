package com.jeevavibeapp.spendwise

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jeevavibeapp.spendwise.core.Analytics
import com.jeevavibeapp.spendwise.core.Backup
import com.jeevavibeapp.spendwise.core.Budget
import com.jeevavibeapp.spendwise.core.Budgets
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.core.Recovery
import com.jeevavibeapp.spendwise.core.Recurring
import com.jeevavibeapp.spendwise.core.SenderBook
import com.jeevavibeapp.spendwise.core.Senders
import com.jeevavibeapp.spendwise.core.Tx
import com.jeevavibeapp.spendwise.data.AppGraph
import com.jeevavibeapp.spendwise.data.CategoryEntity
import com.jeevavibeapp.spendwise.data.QuarantineEntity
import com.jeevavibeapp.spendwise.data.Repo
import com.jeevavibeapp.spendwise.data.SettingsEntity
import com.jeevavibeapp.spendwise.data.TransactionEntity
import com.jeevavibeapp.spendwise.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The whole app.
 *
 * What is NOT here is the point: no embedded interpreter, no WSGI server, no
 * loopback socket, no readiness poll, no "starting your money engine" splash.
 * onCreate opens a database and sets content. Every failure this replaced —
 * "the app engine didn't respond", "Address already in use", a twenty-second
 * startup timeout — was a property of the architecture, not a bug in it.
 *
 * Navigation is four tabs over one back stack of full-page destinations,
 * held in composition rather than delegated to a navigation library. With
 * five pages and no deep links, a graph would be more machinery than the
 * thing it routes.
 */
class NativeMainActivity : ComponentActivity() {

    private val query = MutableStateFlow("")

    /** Read off the manifest and the permission state rather than assumed,
     *  and re-read in onResume because both can change while the app is in
     *  the background. */
    private val smsAccess = MutableStateFlow(SmsAccess.UNAVAILABLE)

    private val smsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        smsAccess.value = readSmsAccess()
        // Only over a ledger that opened: the scanner would otherwise start
        // a recovery on the main thread, or throw from inside this callback.
        if (granted.values.any { it } && ledger.value is AppGraph.Ledger.Ready) {
            // Catch up on anything that arrived while the app was closed.
            lifecycleScope.launch { AppGraph.inboxScanner(this@NativeMainActivity).scan() }
        }
    }

    /** Null while the open runs, which can take seconds when it recovers,
     *  so nothing on screen can reach the ledger before it exists. */
    private val ledger = MutableStateFlow<AppGraph.Ledger?>(null)

    /** Cleared here the moment it is acknowledged, so the dialog does not
     *  wait on the file being deleted behind it. */
    private val notice = MutableStateFlow<AppGraph.Notice?>(null)

    /** So a second tap on "Try again" cannot queue another run behind the
     *  first, each of which may copy the whole ledger. */
    private val retrying = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        smsAccess.value = readSmsAccess()
        setContent { Root() }
        // Off the main thread: the check, a weekly snapshot or a restore can
        // each outlast a frame, and a restore can outlast the ANR timeout.
        lifecycleScope.launch {
            arrive(withContext(Dispatchers.IO) { AppGraph.open(this@NativeMainActivity) })
        }
    }

    override fun onResume() {
        super.onResume()
        smsAccess.value = readSmsAccess()
    }

    private fun arrive(result: AppGraph.Ledger) {
        ledger.value = result
        if (result !is AppGraph.Ledger.Ready) return
        val repo = result.repo

        // Engine.SEED_MERCHANTS files its built-in merchants under category
        // NAMES, and resolveMerchant turns a name into an id by looking in
        // this table — so with no rows here every captured transaction lands
        // uncategorised and the budgets screen has nothing to show.
        lifecycleScope.launch { repo.ensureDefaultCategories() }

        // Only when the permission is already held. The introduction explains
        // what is read before asking, and a request fired from onCreate would
        // put the system dialog in front of that screen — which is the one
        // thing the screen exists to prevent.
        if (smsAccess.value == SmsAccess.GRANTED) {
            lifecycleScope.launch { AppGraph.inboxScanner(this@NativeMainActivity).scan() }
        }

        lifecycleScope.launch {
            notice.value = withContext(Dispatchers.IO) { AppGraph.notice(this@NativeMainActivity) }
        }
    }

    private fun retry() {
        if (retrying.value) return
        retrying.value = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppGraph.retry(this@NativeMainActivity) }
            retrying.value = false
            arrive(result)
        }
    }

    private fun dismissNotice() {
        notice.value = null
        lifecycleScope.launch(Dispatchers.IO) { AppGraph.dismissNotice(this@NativeMainActivity) }
    }

    @Composable
    private fun Root() {
        when (val opened = ledger.collectAsState().value) {
            null -> SpendWiseTheme { Opening() }
            is AppGraph.Ledger.Ready -> Themed(opened.repo)
            is AppGraph.Ledger.Unavailable -> SpendWiseTheme {
                val busy by retrying.collectAsState()
                LedgerFailure(opened, retrying = busy, onRetry = ::retry)
            }
        }
    }

    @Composable
    private fun Themed(repo: Repo) {
        val stored by remember(repo) { repo.settings() }.collectAsState(initial = null)
        val settings = stored
        val untold by notice.collectAsState()
        val access by smsAccess.collectAsState()

        SpendWiseTheme(
            dark = when (settings?.theme) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
        ) {
            // Room has not answered yet. One empty frame is better than
            // flashing the introduction at someone who finished it months
            // ago, which is what rendering the default would do.
            if (settings == null) Surface(Modifier.fillMaxSize()) {}
            else App(repo, settings)

            untold?.let {
                RecoveryNotice(it, rescans = access == SmsAccess.GRANTED,
                    onDismiss = ::dismissNotice)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(repo: Repo, settings: SettingsEntity) {
        var tab by remember { mutableStateOf(Tab.HOME) }
        var stack by remember { mutableStateOf(emptyList<Page>()) }
        var sheet by remember { mutableStateOf<Sheet?>(null) }
        var reportMonth by remember { mutableStateOf(YearMonth.now()) }
        var flash by remember { mutableStateOf<String?>(null) }
        var restoreOffer by remember { mutableStateOf<Backup.Read.Ok?>(null) }
        var editingCategories by remember { mutableStateOf(false) }
        var pasting by remember { mutableStateOf(false) }

        val transactions by remember(repo) { repo.stream() }.collectAsState(initial = emptyList())
        val held by remember(repo) { repo.heldMessages() }.collectAsState(initial = emptyList())
        // The Fraud protection card promises that a charge at or above the
        // high-value amount raises an alert. Repo.raiseFraudAlerts writes
        // them as transactions are filed; this is the read that finally puts
        // them in front of the person who asked to be told.
        val alerts by remember(repo) { repo.fraudAlerts() }.collectAsState(initial = emptyList())
        // Who has been messaging, so a sender that keeps being quarantined
        // can be trusted once and one that keeps bothering the user can be
        // blocked. Without this read the register is write-only.
        val senderRows by remember(repo) { repo.senders() }.collectAsState(initial = emptyList())
        val categories by remember(repo) { repo.categories() }.collectAsState(initial = emptyList())
        val q by query.collectAsState()
        val access by smsAccess.collectAsState()
        // A count, not the queue: the badge is on screen constantly, and
        // building the grouped queue retrains the categoriser.
        val waiting by remember(repo) { repo.reviewCount() }.collectAsState(initial = 0)

        // A key wherever it is used, not just an argument: the app can be
        // left open across midnight, and a cached figure computed against
        // yesterday would quietly stay on screen.
        val today = LocalDate.now()
        val page = stack.lastOrNull()

        // Aggregates come from the same :core functions the JVM tests cover,
        // so what is on screen is what was verified.
        val ledger = remember(transactions) {
            transactions.map {
                Tx(it.id, it.amount, it.type, Repo.localTime(it.occurredAt),
                   it.merchantName ?: it.rawMerchant, it.categoryId)
            }
        }
        val recurring = remember(ledger, today) { Analytics.detectRecurring(ledger, today) }
        val flow = remember(ledger) { Analytics.moneyFlow(ledger) }
        val insights = remember(ledger, recurring, today) { headlines(ledger, recurring, today) }

        val expenseBudgets = remember(categories) { categories.toBudgets("expense") }
        val incomeBudgets = remember(categories) { categories.toBudgets("income") }
        // The lookup, not a lambda over it: :core takes the naming function
        // by value, and a fresh closure on every recomposition would be a new
        // remember key each time and recompute the whole report with it.
        val categoryNames = remember(categories) {
            categories.associateBy({ c -> c.id }, { c -> c.name })
        }

        val sms = remember(access, transactions, held) {
            smsStatus(access, transactions, held)
        }
        val prefs = remember(settings) {
            AppPrefs(settings.currency, settings.theme, settings.autoSaveThreshold,
                settings.confirmThreshold, settings.highValueAmount)
        }

        val snackbar = remember { SnackbarHostState() }

        /** The settings screen shows the last result in place; anywhere else
         *  it has to be said out loud, or an export that opened a file picker
         *  and then finished silently looks like it did nothing. */
        val announce: (String) -> Unit = { message ->
            flash = message
            if (stack.lastOrNull() != Page.SETTINGS) {
                lifecycleScope.launch { snackbar.showSnackbar(message) }
            }
        }

        // No INTERNET permission: everything in and out of this app
        // goes through a picker the user drove, and lands where they chose.

        val saveBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) lifecycleScope.launch {
                announce(
                    if (writeDocument(uri, repo.backupText()))
                        "Backup saved. Keep a copy somewhere that isn't this phone."
                    else "Couldn't write to that file. Try another folder.")
            }
        }

        val saveCsv = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri: Uri? ->
            if (uri != null) lifecycleScope.launch {
                announce(
                    if (writeDocument(uri, repo.csvExport()))
                        "CSV saved. It's a report, not a backup — it can't be restored."
                    else "Couldn't write to that file. Try another folder.")
            }
        }

        val saveHeld = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/plain")
        ) { uri: Uri? ->
            if (uri != null) lifecycleScope.launch {
                announce(
                    if (writeDocument(uri, repo.heldExport()))
                        "Saved. Nothing was sent anywhere — the file is on your phone."
                    else "Couldn't write to that file. Try another folder.")
            }
        }

        val openBackup = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) lifecycleScope.launch {
                val bytes = readDocument(uri)
                if (bytes == null) {
                    announce("Couldn't open that file.")
                } else when (val read = repo.readBackup(bytes)) {
                    // The file is refused with a sentence about the file, and
                    // nothing at all is written until the user has seen what
                    // is in it and said yes.
                    is Backup.Read.Rejected -> announce(read.reason)
                    is Backup.Read.Ok -> restoreOffer = read
                }
            }
        }

        val onBackup: () -> Unit = { saveBackup.launch(backupFilename()) }
        val onExportCsv: () -> Unit = { saveCsv.launch(csvFilename()) }
        val onExportHeld: () -> Unit = { saveHeld.launch("spendwise-unreadable.txt") }
        // Every file type, because a file manager that reports a backup as
        // octet-stream would otherwise grey out the only file that works.
        val onRestore: () -> Unit = { openBackup.launch(arrayOf("*/*")) }
        val onAllowSms: () -> Unit = { requestSms() }

        // Back never exits from a page or an inner tab: it unwinds the stack,
        // then returns to Home, and only then falls through to the system.
        BackHandler(enabled = page != null || tab != Tab.HOME) {
            if (stack.isNotEmpty()) stack = stack.dropLast(1) else tab = Tab.HOME
        }

        if (!settings.onboardingSeen) {
            // No tabs and no top bar: nothing behind them is set up yet.
            // Privacy is the one place this can lead, and it gets the same
            // working export handlers it has everywhere else. The snackbar
            // host is here because those handlers report through it.
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                Box(Modifier.fillMaxSize().padding(pad)) {
                    if (page == Page.PRIVACY) {
                        PrivacyScreen(onBackup = onBackup, onExportCsv = onExportCsv)
                    } else {
                        WelcomeScreen(
                            sms = sms,
                            onAllowSms = onAllowSms,
                            onPrivacy = { stack = listOf(Page.PRIVACY) },
                            onDone = { lifecycleScope.launch { repo.markOnboardingSeen() } },
                        )
                    }
                }
            }
            return
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(page?.title ?: tab.label) },
                    // Words, not glyphs. There is no icon set anywhere in
                    // this app — the whole design is type and space — and a
                    // labelled control is also the one a screen reader can
                    // read without a contentDescription to maintain.
                    navigationIcon = {
                        if (page != null) TextButton(
                            onClick = { stack = stack.dropLast(1) },
                            modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                        ) { Text("Back") }
                    },
                    actions = {
                        if (page == null) {
                            if (tab == Tab.BUDGETS) TextButton(
                                onClick = { editingCategories = true },
                                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                            ) { Text("Categories") }
                            if (tab == Tab.HOME) TextButton(
                                onClick = { stack = stack + Page.REPORT },
                                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                            ) { Text("Report") }
                            // Only when there is something to sort. The
                            // ledger is where the "Needs review" rows are
                            // read, so it is where the way to settle them all
                            // at once belongs.
                            if (tab == Tab.ACTIVITY && waiting > 0) TextButton(
                                onClick = { stack = stack + Page.REVIEW },
                                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                            ) { Text("Review $waiting") }
                            TextButton(
                                onClick = { stack = stack + Page.SETTINGS },
                                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                            ) { Text("Settings") }
                        }
                    },
                )
            },
            bottomBar = {
                if (page == null) NavigationBar {
                    // An alert on a tab nobody opens has not alerted anyone.
                    // The number is in the label because there is no icon set
                    // in this app to hang a badge off — and a screen reader
                    // reads it either way. It counts BOTH kinds the tab
                    // holds: "Alerts (2)" over two flagged charges and five
                    // held messages is a worse lie than no number at all.
                    val waitingAlerts = alerts.size + held.size
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            // The label is the whole affordance. The dot only
                            // gives the selection indicator something to sit
                            // behind — an empty icon slot leaves it drawing an
                            // empty pill over the label.
                            icon = { SelectionDot(selected = tab == entry) },
                            label = {
                                Text(
                                    if (entry == Tab.ALERTS && waitingAlerts > 0)
                                        "${entry.label} ($waitingAlerts)"
                                    else entry.label
                                )
                            },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (page == null && (tab == Tab.HOME || tab == Tab.ACTIVITY)) {
                    FloatingActionButton(
                        onClick = { sheet = Sheet.New },
                        modifier = Modifier.semantics {
                            contentDescription = "Add a transaction"
                        },
                    ) { Text("+", style = MaterialTheme.typography.headlineLarge) }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (page) {
                    null -> when (tab) {
                        Tab.HOME -> DashboardScreen(
                            balance = flow.income - flow.expense,
                            income = flow.income, expense = flow.expense,
                            insights = insights, recurring = recurring,
                            onAdd = { sheet = Sheet.New },
                        )

                        Tab.ACTIVITY -> {
                            // The same four fields the DAO's search covers.
                            // Filtered here because the whole ledger is
                            // already collected for the aggregates above.
                            val matches = remember(transactions, q) { transactions.matching(q) }
                            TransactionsScreen(
                                transactions = matches,
                                query = q,
                                onQuery = { query.value = it },
                                onEdit = { tx -> sheet = Sheet.Edit(tx) },
                                onDelete = { id ->
                                    lifecycleScope.launch {
                                        repo.delete(id)
                                        // The row is only soft-deleted, so
                                        // undo restores the same transaction
                                        // rather than rebuilding a copy.
                                        val choice = snackbar.showSnackbar(
                                            "Transaction deleted",
                                            actionLabel = "Undo",
                                            duration = SnackbarDuration.Long)
                                        if (choice == SnackbarResult.ActionPerformed) {
                                            repo.undelete(id)
                                        }
                                    }
                                },
                                onConfirm = { id, name ->
                                    lifecycleScope.launch {
                                        // What the confirmation bought, not
                                        // "done": the point of naming a payee
                                        // is that the next message from it is
                                        // filed without asking.
                                        if (repo.confirm(id, name)) {
                                            announce("Confirmed. The next one from " +
                                                     "${name.trim()} will be filed for you.")
                                        }
                                    }
                                },
                            )
                        }

                        Tab.BUDGETS -> {
                            val month = YearMonth.from(today)
                            val status = remember(ledger, expenseBudgets, month, today) {
                                Budgets.budgetStatus(ledger, expenseBudgets, month, today)
                            }
                            BudgetsScreen(
                                categories = expenseBudgets,
                                status = status,
                                month = month,
                                incomeCategories = incomeBudgets,
                                onSetBudget = { id, amount ->
                                    lifecycleScope.launch { repo.setBudget(id, amount) }
                                },
                            )
                        }

                        Tab.ALERTS -> AlertsScreen(
                            alerts = alerts,
                            held = held,
                            // Ordered and labelled by :core, so the row a
                            // finger lands on is the one the JVM checks
                            // describe.
                            senders = remember(senderRows) {
                                SenderBook.list(senderRows.map {
                                    SenderBook.Row(
                                        sender = it.sender, display = it.display,
                                        kind = it.kind, bank = it.bank, trust = it.trust,
                                        messageCount = it.messageCount,
                                        quarantinedCount = it.quarantinedCount,
                                    )
                                })
                            },
                            onApprove = { id ->
                                lifecycleScope.launch { announce(describe(repo.approveHeld(id))) }
                            },
                            onReject = { id ->
                                lifecycleScope.launch {
                                    repo.rejectHeld(id)
                                    announce("Discarded. It stays on your phone, out of " +
                                             "your ledger.")
                                }
                            },
                            onBlock = { id ->
                                lifecycleScope.launch {
                                    val who = repo.rejectHeldAndBlock(id)
                                    // The button only exists on a message with a
                                    // sender, so null means the block half failed.
                                    // Leaving that unsaid lets the user assume it
                                    // worked until the sender's next scam is filed.
                                    announce(if (who != null)
                                        "Discarded, and $who is blocked. Anything else it " +
                                        "sends is held without asking."
                                    else "Discarded, but the sender couldn't be blocked, " +
                                         "so its next message is checked like any other.")
                                }
                            },
                            onDismiss = { id ->
                                lifecycleScope.launch { repo.dismissAlert(id) }
                            },
                            onTrust = { sender, trust ->
                                lifecycleScope.launch {
                                    if (repo.setSenderTrust(sender, trust)) {
                                        announce(when (trust) {
                                            Senders.TRUST_BLOCKED ->
                                                "$sender is blocked. Anything else it sends " +
                                                "is held without asking."
                                            Senders.TRUST_TRUSTED ->
                                                "$sender is trusted. Its messages are filed " +
                                                "unless one reads like a scam."
                                            else ->
                                                "$sender is back to being checked like any " +
                                                "other sender."
                                        })
                                    } else {
                                        // Nothing matched, so the row keeps its old
                                        // label. Silence beside it reads as a tap that
                                        // did not register, and a user who believes a
                                        // sender is blocked stops watching for it.
                                        announce("Couldn't change $sender, so nothing was " +
                                                 "saved. Its messages are handled as before.")
                                    }
                                }
                            },
                        )
                    }

                    // Every figure here is decided in :core. The screen is
                    // given finished values rather than a ledger, so nothing
                    // on it is a second, untested implementation of
                    // arithmetic that already exists.
                    Page.REPORT -> {
                        val all = remember(expenseBudgets, incomeBudgets) {
                            expenseBudgets + incomeBudgets
                        }
                        ReportScreen(
                            report = remember(ledger, reportMonth, all, today) {
                                Budgets.buildReport(ledger, reportMonth, today, all)
                            },
                            today = today,
                            forecast = remember(ledger, reportMonth, recurring, today) {
                                Insights.forecast(ledger, reportMonth, today, recurring)
                            },
                            anomalies = remember(ledger, reportMonth) {
                                Insights.anomalies(ledger, reportMonth)
                            },
                            savings = remember(ledger, reportMonth, recurring, categoryNames) {
                                Insights.savingsOpportunities(
                                    ledger, reportMonth, recurring,
                                    categoryName = { id ->
                                        categoryNames[id] ?: Budgets.UNCATEGORISED
                                    })
                            },
                            cashFlow = remember(ledger, today) { Insights.cashFlow(ledger, today) },
                            trends = remember(ledger, categoryNames, today) {
                                Insights.categoryTrends(ledger, today, categoryName = { id ->
                                    categoryNames[id] ?: Budgets.UNCATEGORISED
                                })
                            },
                            merchants = remember(ledger, reportMonth) {
                                Insights.merchantInsights(ledger, reportMonth)
                            },
                            onMonth = { reportMonth = it },
                            onTransaction = { id ->
                                transactions.firstOrNull { it.id == id }
                                    ?.let { sheet = Sheet.Edit(it) }
                            },
                        )
                    }

                    // One question per payee, answered for every capture
                    // behind it. The grouping and the pre-filled suggestions
                    // are Review in :core; nothing on the screen decides
                    // anything the JVM checks do not cover.
                    Page.REVIEW -> {
                        // Collected inside the page rather than beside the
                        // ledger above: building the queue retrains the
                        // categoriser over every categorised row, which is
                        // not work to repeat on each change to the ledger
                        // while this screen is closed.
                        val queue by remember(repo) { repo.reviewQueue() }
                            .collectAsState(initial = emptyList())
                        ReviewScreen(
                            groups = queue,
                            categories = categories,
                            onCategorise = { group, categoryId ->
                                lifecycleScope.launch {
                                    announce(sorted(
                                        repo.categoriseReviewGroup(group, categoryId = categoryId),
                                        categoryNames[categoryId]))
                                }
                            },
                            onCreateCategory = { group, name ->
                                lifecycleScope.launch {
                                    announce(sorted(
                                        repo.categoriseReviewGroup(group, newCategoryName = name),
                                        name.trim()))
                                }
                            },
                            onDiscard = { group ->
                                lifecycleScope.launch {
                                    val n = repo.discardReviewGroup(group)
                                    announce("Removed $n " +
                                             (if (n == 1) "capture." else "captures.") +
                                             " Nothing else was touched.")
                                }
                            },
                        )
                    }

                    Page.SETTINGS -> SettingsScreen(
                        prefs = prefs,
                        sms = sms,
                        onSavePrefs = { edited ->
                            lifecycleScope.launch {
                                repo.updateSettings {
                                    it.copy(
                                        currency = edited.currency,
                                        theme = edited.theme,
                                        autoSaveThreshold = edited.autoSaveThreshold,
                                        confirmThreshold = edited.confirmThreshold,
                                        highValueAmount = edited.highValueAmount,
                                    )
                                }
                                announce("Preferences saved.")
                            }
                        },
                        onAllowSms = onAllowSms,
                        onBackup = onBackup,
                        onRestore = onRestore,
                        onExportCsv = onExportCsv,
                        onExportUnreadable = onExportHeld,
                        onClearUnreviewed = {
                            lifecycleScope.launch {
                                val n = repo.clearUnreviewedCaptures()
                                announce("Cleared $n unreviewed " +
                                         (if (n == 1) "capture." else "captures."))
                            }
                        },
                        onPrivacy = { stack = stack + Page.PRIVACY },
                        onHelp = { stack = stack + Page.HELP },
                        onHowItWorks = { stack = stack + Page.WELCOME },
                        onImport = { pasting = true },
                        flash = flash,
                    )

                    Page.PRIVACY -> PrivacyScreen(
                        onBackup = onBackup,
                        onExportCsv = onExportCsv,
                    )

                    Page.HELP -> HelpScreen(
                        sms = sms,
                        onAllowSms = onAllowSms,
                        onExportUnreadable = onExportHeld,
                        onImport = { pasting = true },
                    )

                    // The introduction, reopened deliberately from settings.
                    // Done closes the page rather than re-recording something
                    // that was recorded the first time.
                    Page.WELCOME -> WelcomeScreen(
                        sms = sms,
                        onAllowSms = onAllowSms,
                        onPrivacy = { stack = stack + Page.PRIVACY },
                        onDone = { stack = stack.dropLast(1) },
                    )
                }
            }
        }

        val editing = sheet
        if (editing != null) {
            // The row is carried in the sheet's own state rather than looked
            // up by id: a soft delete takes it out of the stream, and a
            // lookup would drop the transaction out from under the undo
            // panel the moment the user pressed Delete.
            val existing = (editing as? Sheet.Edit)?.tx
            AddEditSheet(
                categories = categories,
                resolveMerchant = { typed -> repo.resolveMerchant(typed, null, null).merchantName },
                onSave = { tx -> lifecycleScope.launch { repo.save(tx) } },
                onDismiss = { sheet = null },
                existing = existing,
                onDelete = { tx -> lifecycleScope.launch { repo.delete(tx.id) } },
                onRestore = { tx -> lifecycleScope.launch { repo.undelete(tx.id) } },
            )
        }

        if (editingCategories) {
            CategoryDialog(
                categories = categories,
                onCreate = { name, type ->
                    lifecycleScope.launch {
                        announce(describe(repo.createCategory(name, type), name.trim()))
                    }
                },
                onRename = { id, name ->
                    lifecycleScope.launch {
                        announce(describe(repo.renameCategory(id, name), name.trim()))
                    }
                },
                onDismiss = { editingCategories = false },
            )
        }

        if (pasting) {
            PasteDialog(
                onDismiss = { pasting = false },
                onRead = { body ->
                    pasting = false
                    lifecycleScope.launch { announce(describe(repo.ingestSms(null, body))) }
                },
            )
        }

        restoreOffer?.let { offer ->
            RestoreDialog(
                summary = offer.summary,
                onDismiss = { restoreOffer = null },
                onConfirm = { replace ->
                    restoreOffer = null
                    lifecycleScope.launch { announce(describe(repo.applyRestore(offer.doc, replace))) }
                },
            )
        }
    }

    // Here rather than in ui/ because each one exists to reach a repository
    // call that no screen in that package has a parameter for.

    /**
     * Creating and renaming categories.
     *
     * Renaming is an edit of the row transactions already point at, never a
     * new category plus a move: the id is what the ledger stores, so a rename
     * cannot orphan a single transaction.
     */
    @Composable
    private fun CategoryDialog(
        categories: List<CategoryEntity>,
        onCreate: (name: String, type: String) -> Unit,
        onRename: (id: String, name: String) -> Unit,
        onDismiss: () -> Unit,
    ) {
        var newName by remember { mutableStateOf("") }
        var newType by remember { mutableStateOf("expense") }
        var renamingId by remember { mutableStateOf<String?>(null) }
        var renameTo by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Categories") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Transactions are filed under these. Renaming one keeps " +
                         "everything already filed under it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = newType == "expense",
                            onClick = { newType = "expense" },
                            label = { Text("Expense") },
                            modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                        )
                        FilterChip(
                            selected = newType == "income",
                            onClick = { newType = "income" },
                            label = { Text("Income") },
                            modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                        )
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = { onCreate(newName, newType); newName = "" },
                            modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                        ) { Text("Add") }
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                    for (c in categories) {
                        if (renamingId == c.id) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = renameTo,
                                onValueChange = { renameTo = it },
                                label = { Text("Rename ${c.name}") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = Tokens.minTouchTarget),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    onClick = { onRename(c.id, renameTo); renamingId = null },
                                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                                ) { Text("Save") }
                                TextButton(
                                    onClick = { renamingId = null },
                                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                                ) { Text("Cancel") }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .heightIn(min = Tokens.minTouchTarget),
                            ) {
                                Text(c.name, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium)
                                Text(if (c.type == "income") "INCOME" else "EXPENSE",
                                    style = MicroLabel,
                                    color = if (c.type == "income") Income else Expense)
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = { renamingId = c.id; renameTo = c.name },
                                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                                ) { Text("Rename") }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Done") }
            },
        )
    }

    /**
     * Paste a bank message.
     *
     * The only capture path the no-SMS build has, and the one anybody can use
     * to check what SpendWise would make of a message before granting it the
     * inbox. It runs the same [Repo.ingestSms] the receiver does.
     */
    @Composable
    private fun PasteDialog(onDismiss: () -> Unit, onRead: (String) -> Unit) {
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Paste a bank message") },
            text = {
                Column {
                    Text("It is read on this phone, by the same parser that reads " +
                         "captured messages. Nothing is sent anywhere.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = body,
                        onValueChange = { body = it },
                        label = { Text("Message") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { if (body.isNotBlank()) onRead(body) },
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Read it") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Cancel") }
            },
        )
    }

    /**
     * What is in the file, before anything is written.
     *
     * Replace is the only mode that can lose data, so it is a switch the user
     * has to reach for and the button under it changes its word. Nobody
     * should be able to empty their ledger by pressing the default.
     */
    @Composable
    private fun RestoreDialog(
        summary: Backup.Summary,
        onDismiss: () -> Unit,
        onConfirm: (replace: Boolean) -> Unit,
    ) {
        var replace by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text("${summary.transactions} transactions, ${summary.categories} " +
                         "categories, ${summary.learning} learned merchants.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (summary.first != null && summary.last != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("${summary.first} to ${summary.last}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (summary.dropped > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text("${summary.dropped} rows in the file couldn't be read and " +
                             "will be skipped.",
                            style = MaterialTheme.typography.bodySmall, color = Warn)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = Tokens.minTouchTarget),
                    ) {
                        Text("Replace everything on this phone",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = replace, onCheckedChange = { replace = it })
                    }
                    Text(
                        if (replace)
                            "Your current transactions, categories and learning are " +
                            "deleted first. Use this on a new phone."
                        else
                            "Anything already here is kept. Only what this ledger does " +
                            "not have is added, so restoring the same file twice is safe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (replace) Expense else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onConfirm(replace) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (replace) Expense else MaterialTheme.colorScheme.primary),
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text(if (replace) "Replace everything" else "Merge into my ledger") }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                ) { Text("Cancel") }
            },
        )
    }

    // Through the system picker, both ways. The app has no INTERNET
    // permission and no folder of its own that anything else can reach, so a
    // file only exists where the user put it.

    private fun writeDocument(uri: Uri, text: String): Boolean = try {
        contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } != null
    } catch (t: Throwable) {
        // A revoked grant, a full disk, a provider that lied about being
        // writable. The caller says so; nothing here is worth a crash.
        false
    }

    private fun readDocument(uri: Uri): ByteArray? = try {
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    /** Three states, not two: the no-SMS flavour declares neither permission,
     *  and asking for one the manifest does not carry is denied instantly by
     *  the system — leaving the user tapping a button that can never work. */
    private fun readSmsAccess(): SmsAccess {
        val declared = declaredPermissions()
        val wanted = SMS_PERMISSIONS.filter { it in declared }
        if (wanted.isEmpty()) return SmsAccess.UNAVAILABLE
        val granted = wanted.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        return if (granted) SmsAccess.GRANTED else SmsAccess.DENIED
    }

    private fun requestSms() {
        val declared = declaredPermissions()
        val missing = SMS_PERMISSIONS.filter {
            it in declared &&
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) smsPermission.launch(missing.toTypedArray())
    }

    private fun declaredPermissions(): Set<String> = try {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toSet().orEmpty()
    } catch (t: Throwable) {
        emptySet()
    }

    private companion object {
        val SMS_PERMISSIONS = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
    }
}

/** Material draws a selection pill behind a navigation item's icon. With an
 *  empty slot that pill is an empty blob over the label, which reads as a
 *  missing asset rather than as restraint. */
@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
    )
}

private enum class Tab(val label: String) {
    HOME("Home"), ACTIVITY("Activity"), BUDGETS("Budgets"), ALERTS("Alerts")
}

/** Full pages, reached over a back stack rather than by swapping a tab: each
 *  one covers the bars, and back returns to whatever it covered. */
private enum class Page(val title: String) {
    REPORT("Report"),
    REVIEW("Review"),
    SETTINGS("Settings"),
    PRIVACY("Privacy"),
    HELP("Help"),
    WELCOME("How SpendWise works"),
}

private sealed interface Sheet {
    data object New : Sheet
    data class Edit(val tx: TransactionEntity) : Sheet
}

private val CaptureDate: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun List<CategoryEntity>.toBudgets(type: String): List<Budget> =
    filter { if (type == "income") it.type == "income" else it.type != "income" }
        .map { Budget(categoryId = it.id, name = it.name, amount = it.budgetAmount, color = it.color) }

/** The same four fields the DAO's search query covers, so switching between
 *  the two would not change what a search finds. */
private fun List<TransactionEntity>.matching(query: String): List<TransactionEntity> {
    if (query.isBlank()) return this
    return filter { t ->
        listOfNotNull(t.merchantName, t.rawMerchant, t.notes, t.referenceNumber)
            .any { it.contains(query, ignoreCase = true) }
    }
}

/**
 * What the settings and help screens say about capture.
 *
 * `unreadable` is the quarantine. Every held message cleared the parser's
 * "looks transactional" gate and still did not reach the ledger, which is
 * what the screen means by a message that could not be read; its sender is
 * the closest thing this schema records to a message *type*. Messages that
 * failed to parse outright are not stored anywhere, so counting them would
 * mean inventing a number — this counts what actually exists, which is also
 * exactly the set that "Export unreadable messages" writes out.
 */
private fun smsStatus(
    access: SmsAccess,
    transactions: List<TransactionEntity>,
    held: List<QuarantineEntity>,
): SmsStatus {
    val captured = transactions.filter { it.source == "sms" }
    return SmsStatus(
        access = access,
        captured = captured.size,
        queued = held.size,
        lastCapture = captured.maxByOrNull { it.occurredAt }
            ?.let { Repo.localTime(it.occurredAt).toLocalDate().format(CaptureDate) },
        unreadableKinds = held.mapNotNull { it.sender }.distinct().size,
        unreadableTotal = held.size,
        unreviewed = captured.count { it.status != "confirmed" },
    )
}

/** Short, checkable statements. Anything that cannot cite the number behind
 *  it does not belong on the home screen. */
private fun headlines(
    ledger: List<Tx>,
    recurring: List<Recurring>,
    today: LocalDate,
): List<String> {
    if (ledger.isEmpty()) return emptyList()
    val month = YearMonth.from(today)
    val out = mutableListOf<String>()
    Insights.forecast(ledger, month, today, recurring)?.let { f ->
        out += "On track for about ${Insights.money(f.projected)} this month."
    }
    Insights.anomalies(ledger, month).firstOrNull()?.let { out += it.explanation }
    Insights.savingsOpportunities(ledger, month, recurring).firstOrNull()
        ?.let { out += it.detail }
    return out
}

private fun backupFilename(): String = "spendwise-backup-${LocalDate.now()}.json"

private fun csvFilename(): String = "spendwise-${LocalDate.now()}.csv"

private fun describe(result: Repo.Ingest): String = when (result) {
    is Repo.Ingest.Captured ->
        if (result.needsCategory) "Added. Give it a category when you have a moment."
        else "Added to your ledger."
    is Repo.Ingest.Duplicate -> "That one is already in your ledger."
    is Repo.Ingest.Held -> "Held for you to check: ${result.reason}"
    Repo.Ingest.NotFinancial -> "That doesn't read as a transaction, so nothing was added."
}

/** Rows written, not "done": the whole point of the screen is that one tap
 *  settled more than one row, and a bare confirmation hides the number that
 *  makes it worth using. */
private fun sorted(rows: Int, category: String?): String = when {
    rows == 0 -> "Nothing was sorted — those captures are no longer waiting."
    category == null -> "Sorted $rows ${if (rows == 1) "capture" else "captures"}."
    else -> "Sorted $rows ${if (rows == 1) "capture" else "captures"} into $category."
}

private fun describe(result: Repo.CategoryResult, name: String): String = when (result) {
    Repo.CategoryResult.CREATED -> "Added $name."
    Repo.CategoryResult.RENAMED -> "Renamed to $name."
    Repo.CategoryResult.DUPLICATE -> "There is already a category called $name."
    Repo.CategoryResult.INVALID -> "A category needs a name."
}

/** What actually landed, not "done". The counts come off the plan :core
 *  produced, so they describe the rows that were written rather than the rows
 *  the file claimed to contain. */
private fun describe(plan: Backup.Plan): String {
    val added = plan.totalAdded
    val skipped = plan.totalSkipped
    val head = if (plan.clearFirst) "Replaced your ledger with $added rows."
               else "Added $added rows."
    return if (skipped == 0) head
           else "$head Skipped $skipped already here or unreadable."
}

private val SnapshotStamp: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy 'at' HH:mm", Locale.ENGLISH)

private fun stamp(snapshot: String?): String? =
    snapshot?.let(Recovery::takenAt)?.format(SnapshotStamp)

/** Only once opening has taken longer than a glance. A label on every
 *  ordinary launch would flicker, and a blank screen through a recovery that
 *  takes seconds reads as a hang. */
@Composable
private fun Opening() {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(700)
        slow = true
    }
    Surface(Modifier.fillMaxSize()) {
        if (slow) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Opening your ledger…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Instead of a crash, when the ledger will not open.
 *
 * In the order a frightened person needs it: what happened, that the data
 * still exists and where, and a way to try again. A lock gets different words
 * from damage, because telling someone whose data is fine that it is damaged
 * is a harm of its own.
 */
@Composable
private fun LedgerFailure(
    failure: AppGraph.Ledger.Unavailable,
    retrying: Boolean,
    onRetry: () -> Unit,
) {
    val stopped = failure.stopped
    val where = failure.where
    val untouched = stopped.keptAs.isEmpty() && stopped.restoredFrom == null
    val files = if (untouched) "Nothing was changed or deleted."
                else "Nothing has been deleted: every file SpendWise moved is kept, listed below."
    val (title, body) = when {
        stopped.outcome == Recovery.Outcome.BUSY -> "Your ledger is busy" to
            "Something else on this phone was still writing to it — usually a bank message " +
            "being saved — so it couldn't be opened just now. That is not damage. Give it a " +
            "moment, then try again. $files"
        stopped.failure == Recovery.Failure.MIGRATION -> "SpendWise couldn't finish updating" to
            "This version couldn't bring your ledger up to date, so it stopped rather than " +
            "risk it. Your transactions are on this phone, as the previous version left " +
            "them. $files An update to SpendWise is the likely fix."
        stopped.failure == Recovery.Failure.CORRUPTION -> "Your ledger couldn't be read" to
            "The file your transactions are kept in is damaged, and SpendWise couldn't " +
            "repair it from a saved copy. $files"
        else -> "SpendWise couldn't open your ledger" to
            "Opening it failed for a reason SpendWise doesn't recognise. A phone that is " +
            "out of storage is the usual one, so freeing some space is worth trying " +
            "first. $files"
    }

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.screenPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
        ) {
            FailureCard {
                Text("SPENDWISE", style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(10.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onRetry,
                    enabled = !retrying,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTouchTarget),
                ) { Text(if (retrying) "Trying again…" else "Try again") }
            }

            Text("WHERE YOUR DATA IS", style = SectionHeading,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            FailureCard {
                PathRow("Your ledger", null, where.ledger)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 14.dp))
                val copies = stopped.snapshots
                PathRow(
                    "Saved copies",
                    if (copies.isEmpty()) "None yet."
                    else "${copies.size} on this phone, the newest from ${stamp(copies.first())}.",
                    where.snapshots,
                )
                for (path in where.kept) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 14.dp))
                    PathRow("Kept as it was when it failed", null, path)
                }
            }

            // Busy is not a state anyone needs warning off a reinstall for.
            if (stopped.outcome == Recovery.Outcome.SAFE_MODE) FailureCard {
                Text("Don't uninstall SpendWise or clear its storage. Either one deletes " +
                     "these files, and there is no other copy of them.",
                    style = MaterialTheme.typography.bodyMedium, color = Expense)
            }

            stopped.detail?.let { detail ->
                Text("WHAT THE PHONE REPORTED", style = SectionHeading,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

/**
 * A recovery that let the app open, said once. A restore means entries may be
 * missing and a fresh start means all of them are, so neither can be silent —
 * and only the button clears it, because a tap beside the dialog is not
 * someone having read that their history changed.
 */
@Composable
private fun RecoveryNotice(notice: AppGraph.Notice, rescans: Boolean, onDismiss: () -> Unit) {
    val (title, body) = when {
        notice.outcome == Recovery.Outcome.STARTED_FRESH -> "Your ledger was started again" to
            "The file your transactions were kept in couldn't be read, and there was no " +
            "working saved copy to go back to, so SpendWise started a new, empty one."
        notice.withoutLoss -> "An update was retried" to
            "Updating your ledger failed the first time, so SpendWise put back the copy it " +
            "had saved just before and tried again. It worked, and nothing was lost."
        else -> "Your ledger was restored" to
            "The file your transactions were kept in was damaged, so SpendWise put back the " +
            "copy it saved on ${stamp(notice.restoredFrom) ?: "an earlier date"}. Anything " +
            "recorded after that is missing" +
            (if (rescans) ". Bank messages still in your inbox are read again, but anything " +
                "you entered yourself will need adding again." else ".")
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (!notice.withoutLoss && notice.where.kept.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("The unreadable file was kept, not deleted. Uninstalling or clearing " +
                         "storage would delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (path in notice.where.kept) {
                        Spacer(Modifier.height(4.dp))
                        Text(path, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
            ) { Text("OK") }
        },
    )
}

@Composable
private fun FailureCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

@Composable
private fun PathRow(label: String, detail: String?, path: String) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    if (detail != null) {
        Spacer(Modifier.height(2.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(4.dp))
    Text(path, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
