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
import com.jeevavibeapp.spendwise.core.Recurring
import com.jeevavibeapp.spendwise.core.Tx
import com.jeevavibeapp.spendwise.data.AppGraph
import com.jeevavibeapp.spendwise.data.CategoryEntity
import com.jeevavibeapp.spendwise.data.QuarantineEntity
import com.jeevavibeapp.spendwise.data.Repo
import com.jeevavibeapp.spendwise.data.SettingsEntity
import com.jeevavibeapp.spendwise.data.TransactionEntity
import com.jeevavibeapp.spendwise.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
        if (granted.values.any { it }) {
            // Catch up on anything that arrived while the app was closed.
            lifecycleScope.launch { AppGraph.inboxScanner(this@NativeMainActivity).scan() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppGraph.repo(this)
        smsAccess.value = readSmsAccess()

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

        setContent { Root(repo) }
    }

    override fun onResume() {
        super.onResume()
        smsAccess.value = readSmsAccess()
    }

    // ── Theme and the first-run gate ─────────────────────────────────────

    @Composable
    private fun Root(repo: Repo) {
        val stored by remember(repo) { repo.settings() }.collectAsState(initial = null)
        val settings = stored

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
        }
    }

    // ── The app ──────────────────────────────────────────────────────────

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
        val categories by remember(repo) { repo.categories() }.collectAsState(initial = emptyList())
        val q by query.collectAsState()
        val access by smsAccess.collectAsState()

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

        // ── Files. No INTERNET permission: everything in and out of this app
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
                    Tab.entries.forEach { entry ->
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            // The label is the whole affordance. The dot only
                            // gives the selection indicator something to sit
                            // behind — an empty icon slot leaves it drawing an
                            // empty pill over the label.
                            icon = { SelectionDot(selected = tab == entry) },
                            label = { Text(entry.label) },
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

                        Tab.ALERTS -> HeldMessagesScreen(
                            held = held,
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

    // ── Dialogs ──────────────────────────────────────────────────────────
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

    // ── Storage ──────────────────────────────────────────────────────────
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

    // ── SMS permission ───────────────────────────────────────────────────

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

// ── Destinations ─────────────────────────────────────────────────────────

private enum class Tab(val label: String) {
    HOME("Home"), ACTIVITY("Activity"), BUDGETS("Budgets"), ALERTS("Alerts")
}

/** Full pages, reached over a back stack rather than by swapping a tab: each
 *  one covers the bars, and back returns to whatever it covered. */
private enum class Page(val title: String) {
    REPORT("Report"),
    SETTINGS("Settings"),
    PRIVACY("Privacy"),
    HELP("Help"),
    WELCOME("How SpendWise works"),
}

private sealed interface Sheet {
    data object New : Sheet
    data class Edit(val tx: TransactionEntity) : Sheet
}

// ── Derivations ──────────────────────────────────────────────────────────

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

// ── Wording ──────────────────────────────────────────────────────────────

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
