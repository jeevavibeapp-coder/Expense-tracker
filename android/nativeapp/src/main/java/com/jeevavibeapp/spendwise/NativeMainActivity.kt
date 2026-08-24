package com.jeevavibeapp.spendwise

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jeevavibeapp.spendwise.core.Analytics
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.data.AppGraph
import com.jeevavibeapp.spendwise.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * The whole app.
 *
 * What is NOT here is the point: no embedded interpreter, no WSGI server, no
 * loopback socket, no readiness poll, no "starting your money engine" splash.
 * onCreate opens a database and sets content. Every failure this replaced —
 * "the app engine didn't respond", "Address already in use", a twenty-second
 * startup timeout — was a property of the architecture, not a bug in it.
 */
class NativeMainActivity : ComponentActivity() {

    private val query = MutableStateFlow("")

    private val smsPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) {
            // Catch up on anything that arrived while the app was closed.
            lifecycleScope.launch { AppGraph.inboxScanner(this@NativeMainActivity).scan() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppGraph.repo(this)

        setContent {
            SpendWiseTheme {
                var tab by remember { mutableIntStateOf(0) }
                val transactions by repo.dao.recent().collectAsState(initial = emptyList())
                val held by repo.dao.held().collectAsState(initial = emptyList())
                val q by query.collectAsState()

                // Aggregates come from the same :core functions the JVM tests
                // cover, so what is on screen is what was verified.
                val ledger = remember(transactions) {
                    transactions.map {
                        com.jeevavibeapp.spendwise.core.Tx(
                            it.id, it.amount, it.type,
                            com.jeevavibeapp.spendwise.data.Repo.localTime(it.occurredAt),
                            it.merchantName ?: it.rawMerchant, it.categoryId)
                    }
                }
                val flow = remember(ledger) { Analytics.moneyFlow(ledger) }
                val recurring = remember(ledger) {
                    Analytics.detectRecurring(ledger, LocalDate.now())
                }
                val insights = remember(ledger) { headlines(ledger) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            listOf("Home" to 0, "Activity" to 1, "Alerts" to 2)
                                .forEach { (label, index) ->
                                    NavigationBarItem(
                                        selected = tab == index,
                                        onClick = { tab = index },
                                        icon = {},
                                        label = { Text(label) },
                                    )
                                }
                        }
                    },
                    floatingActionButton = {
                        if (tab != 2) FloatingActionButton(onClick = { /* add sheet */ }) {
                            Text("+")
                        }
                    },
                ) { pad ->
                    Surface(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> DashboardScreen(
                                balance = flow.income - flow.expense,
                                income = flow.income, expense = flow.expense,
                                insights = insights, recurring = recurring,
                                onAdd = {},
                            )
                            1 -> TransactionsScreen(
                                transactions = transactions.filter { t ->
                                    q.isBlank() ||
                                    (t.merchantName ?: t.rawMerchant ?: "")
                                        .contains(q, ignoreCase = true)
                                },
                                query = q,
                                onQuery = { query.value = it },
                                onDelete = { id ->
                                    lifecycleScope.launch { repo.dao.setDeleted(id, true) }
                                },
                            )
                            else -> HeldMessagesScreen(
                                held = held,
                                onApprove = { id ->
                                    lifecycleScope.launch {
                                        repo.dao.setQuarantineStatus(id, "approved")
                                    }
                                },
                                onReject = { id ->
                                    lifecycleScope.launch {
                                        repo.dao.setQuarantineStatus(id, "rejected")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        requestSmsIfDeclared()
    }

    /** Only ask for what this build actually declares. The no-SMS flavour
     *  strips the permissions, and requesting one the manifest does not carry
     *  gets an instant denial — which would leave the app nagging forever to
     *  allow something it never asks for. */
    private fun requestSmsIfDeclared() {
        val declared = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toSet().orEmpty()
        } catch (t: Throwable) { emptySet() }

        val wanted = listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)
            .filter { it in declared }
        if (wanted.isEmpty()) return

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) smsPermission.launch(missing.toTypedArray())
        else lifecycleScope.launch { AppGraph.inboxScanner(this).scan() }
    }

    /** Short, checkable statements. Anything that cannot cite the number
     *  behind it does not belong on the home screen. */
    private fun headlines(ledger: List<com.jeevavibeapp.spendwise.core.Tx>): List<String> {
        if (ledger.isEmpty()) return emptyList()
        val month = YearMonth.now()
        val out = mutableListOf<String>()
        Insights.forecast(ledger, month, LocalDate.now(),
            Analytics.detectRecurring(ledger, LocalDate.now()))?.let { f ->
            out += "On track for about ${Insights.money(f.projected)} this month."
        }
        Insights.anomalies(ledger, month).firstOrNull()?.let { out += it.explanation }
        Insights.savingsOpportunities(ledger, month,
            Analytics.detectRecurring(ledger, LocalDate.now()))
            .firstOrNull()?.let { out += it.detail }
        return out
    }
}
