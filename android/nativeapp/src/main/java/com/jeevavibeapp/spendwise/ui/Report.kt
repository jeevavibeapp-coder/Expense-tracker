package com.jeevavibeapp.spendwise.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jeevavibeapp.spendwise.core.Anomaly
import com.jeevavibeapp.spendwise.core.CashFlowMonth
import com.jeevavibeapp.spendwise.core.CategoryLine
import com.jeevavibeapp.spendwise.core.CategoryTrend
import com.jeevavibeapp.spendwise.core.DaySpend
import com.jeevavibeapp.spendwise.core.Forecast
import com.jeevavibeapp.spendwise.core.Insights
import com.jeevavibeapp.spendwise.core.MerchantInsight
import com.jeevavibeapp.spendwise.core.MonthReport
import com.jeevavibeapp.spendwise.core.Opportunity
import com.jeevavibeapp.spendwise.core.Recurring
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The monthly report.
 *
 * Every number here is decided in :core and covered by the JVM tests — this
 * file chooses only what a number looks like. That split is why the screen
 * takes finished [MonthReport]/[Forecast]/[Anomaly] values rather than a
 * ledger: a screen that aggregated its own rows would be a second, untested
 * implementation of arithmetic that already exists.
 *
 * The other rule, taken from `insights.py`: a section appears only when there
 * is evidence for it. The core functions already return null or an empty list
 * below their minimum-evidence gates, so "has evidence" here means "the core
 * gave me something", and a new ledger gets a short honest page instead of a
 * wall of empty placeholders.
 */

/** A zero day is a fact worth drawing, so its bar keeps a visible stub —
 *  drop it to nothing and a quiet Tuesday becomes indistinguishable from a
 *  day the chart never had data for. */
private const val MIN_BAR_FRACTION = 0.04f

/** Below this a merchant's change is inside the noise of a normal month and
 *  is not worth a coloured number. Matches the report template. */
private const val MERCHANT_CHANGE_FLOOR = 15

private val DayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/**
 * @param report the month being read, from `Budgets.buildReport`.
 * @param today used only to decide whether [report] is the month in progress,
 *   which changes both the comparison wording and whether the user is allowed
 *   to page forward.
 * @param onTransaction jump to one transaction. Null hides the affordance
 *   rather than offering a button that does nothing.
 */
@Composable
fun ReportScreen(
    report: MonthReport,
    today: LocalDate,
    forecast: Forecast? = null,
    anomalies: List<Anomaly> = emptyList(),
    savings: List<Opportunity> = emptyList(),
    cashFlow: List<CashFlowMonth> = emptyList(),
    trends: List<CategoryTrend> = emptyList(),
    merchants: List<MerchantInsight> = emptyList(),
    onMonth: (YearMonth) -> Unit = {},
    onTransaction: ((String) -> Unit)? = null,
) {
    val isCurrent = report.month == YearMonth.from(today)

    LazyColumn(
        contentPadding = PaddingValues(Tokens.screenPadding, 8.dp, Tokens.screenPadding, 96.dp),
        verticalArrangement = Arrangement.spacedBy(Tokens.gutter),
    ) {
        item { MonthNav(report, canGoForward = !isCurrent, onMonth = onMonth) }
        item { Hero(report) }

        // Against a month with nothing in it, "you spent ₹4,200 more" is
        // arithmetic against a hole rather than a comparison.
        if (report.prevExpense > 0) item { VersusLastMonth(report, isCurrent) }

        if (report.txCount > 0) item { StatStrip(report) }

        if (report.expense > 0 && report.daily.isNotEmpty()) {
            section("Day by day") { DailyBars(report.daily) }
        }

        if (report.categories.isNotEmpty()) {
            section("By category") { CategoryBreakdown(report.categories) }
        }

        forecast?.let { f ->
            section("Where this month lands") { ForecastCard(f) }
        }

        if (anomalies.isNotEmpty()) {
            section("Unusually large") { AnomalyList(anomalies, onTransaction) }
        }

        if (savings.isNotEmpty()) {
            section("Where the money goes") { SavingsList(savings) }
        }

        // One live month is a bar with nothing to compare against and a
        // running total equal to itself.
        if (cashFlow.count { !it.empty } > 1) {
            section("Cash flow") { CashFlowChart(cashFlow) }
        }

        if (trends.isNotEmpty()) {
            section("Category trends") { TrendList(trends) }
        }

        if (merchants.isNotEmpty()) {
            section("Merchants this month") { MerchantList(merchants) }
        }

        if (report.txCount == 0) {
            item {
                ReportCard {
                    Text("Nothing in ${report.label}",
                        style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("No transactions were recorded this month.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun LazyListScope.section(heading: String, body: @Composable () -> Unit) {
    item { Heading(heading) }
    item { body() }
}

// ── chrome ────────────────────────────────────────────────────────────────

@Composable
private fun MonthNav(report: MonthReport, canGoForward: Boolean, onMonth: (YearMonth) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavChevron("‹", "Previous month", enabled = true) { onMonth(report.prevMonth) }
            Text(
                report.label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Kept in place and disabled rather than removed: dropping the
            // control shifts the month label sideways on the current month,
            // which reads as the layout breaking on the one month people open
            // most.
            NavChevron("›", "Next month", enabled = canGoForward) { onMonth(report.nextMonth) }
        }
    }
}

@Composable
private fun NavChevron(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = Tokens.minTouchTarget)
            .semantics { contentDescription = label },
    ) {
        Text(glyph, style = MaterialTheme.typography.headlineLarge)
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text.uppercase(),
        style = SectionHeading,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp),
    )
}

@Composable
private fun ReportCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp), content = content) }
}

// ── hero ──────────────────────────────────────────────────────────────────

@Composable
private fun Hero(report: MonthReport) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(Tokens.cardRadius),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("SPENT IN ${report.label.substringBefore(' ').uppercase()}",
                style = MicroLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text("₹", style = MoneyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(Insights.money(report.expense).removePrefix("₹"), style = MoneyLarge)
            }
            Spacer(Modifier.height(18.dp))

            // Spending past your income is stated, not clipped to a
            // comforting "₹0 saved" — the template did that, and it is the
            // one month where the number matters most.
            val overspent = report.saved < 0
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill("↓ INCOME", Insights.money(report.income), null, Modifier.weight(1f))
                HeroPill(
                    label = if (overspent) "OVERSPENT" else "SAVED",
                    value = Insights.money(abs(report.saved)),
                    note = report.saveRate?.let {
                        if (it >= 0) "$it% of income" else "${-it}% beyond income"
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HeroPill(label: String, value: String, note: String?, modifier: Modifier = Modifier) {
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
            // Amount and rate on separate lines: packed onto one line at this
            // type size they wrapped mid-value and broke the pill.
            Text(value, style = MoneyMedium)
            note?.let {
                Text(it, style = MicroLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VersusLastMonth(report: MonthReport, isCurrent: Boolean) {
    // A month in progress is compared with the same span of the month before
    // it — :core truncates the comparison, and the sentence has to say so or
    // the user reads a partial month as a real drop.
    val span = if (isCurrent) " over the same days" else ""
    val prev = Insights.money(report.prevExpense)
    val delta = report.expenseDelta
    val line = when {
        delta > 0 -> "You spent ${Insights.money(delta)} more than last month$span ($prev)."
        delta < 0 -> "Nice — ${Insights.money(-delta)} less than last month$span ($prev)."
        else -> "Same as last month$span ($prev)."
    }
    val tint = when {
        delta > 0 -> Expense
        delta < 0 -> Income
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    ReportCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(tint))
            Spacer(Modifier.width(12.dp))
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StatStrip(report: MonthReport) {
    ReportCard {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("TRANSACTIONS", report.txCount.toString(), Modifier.weight(1f))
            Stat("NO-SPEND DAYS", report.noSpendDays.toString(), Modifier.weight(1f))
            Stat("PRICIEST DAY",
                report.highestDay?.let { "Day ${it.date.dayOfMonth}" } ?: "–",
                Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MicroLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MoneyRow, maxLines = 1)
    }
}

// ── this month's own numbers ──────────────────────────────────────────────

@Composable
private fun DailyBars(daily: List<DaySpend>) {
    val peak = daily.maxOf { it.amount }
    ReportCard {
        Row(
            Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            daily.forEach { day ->
                Bar(
                    fraction = if (peak > 0) (day.amount / peak).toFloat() else 0f,
                    color = if (day.amount > 0) Primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AxisLabel("1")
            AxisLabel(daily.size.toString())
        }
    }
}

@Composable
private fun CategoryBreakdown(categories: List<CategoryLine>) {
    val peak = categories.maxOf { it.value }
    ReportCard {
        categories.forEachIndexed { i, c ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            val tint = hexColor(c.color, MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(5.dp)).background(tint))
                Spacer(Modifier.width(8.dp))
                Text(c.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (c.delta != 0.0) {
                    Text(
                        (if (c.delta > 0) "▲ " else "▼ ") + Insights.money(abs(c.delta)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (c.delta > 0) Expense else Income,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(Insights.money(c.value), style = MoneyRow)
            }
            Spacer(Modifier.height(6.dp))
            Meter(if (peak > 0) (c.value / peak).toFloat() else 0f, tint)
        }
    }
}

// ── forecast ──────────────────────────────────────────────────────────────

@Composable
private fun ForecastCard(f: Forecast) {
    ReportCard {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(Insights.money(f.projected), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.width(8.dp))
            Text("projected", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Meter(if (f.projected > 0) (f.spent / f.projected).toFloat() else 0f, Primary)
        Spacer(Modifier.height(8.dp))

        Text(
            buildString {
                append("${Insights.money(f.spent)} spent over ${f.elapsedDays} ")
                append(if (f.elapsedDays == 1) "day" else "days")
                append(" (${Insights.money(f.runRate)}/day), ${f.remainingDays} ")
                append(if (f.remainingDays == 1) "day" else "days")
                append(" to go.")
                if (f.committed > 0) {
                    append(" Includes ${Insights.money(f.committed)} of recurring charges ")
                    append("already due.")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val vsPrev = f.vsPrev
        val prevTotal = f.prevTotal
        if (vsPrev != null && prevTotal != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (vsPrev > 0)
                    "${Insights.money(vsPrev)} above last month's ${Insights.money(prevTotal)}"
                else
                    "${Insights.money(-vsPrev)} below last month's ${Insights.money(prevTotal)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (vsPrev > 0) Expense else Income,
            )
        }

        if (f.upcoming.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            f.upcoming.forEach { UpcomingRow(it) }
        }
    }
}

@Composable
private fun UpcomingRow(r: Recurring) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(r.name, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(r.nextDue.format(DayMonth), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(Insights.money(r.amount), style = MoneyRow)
    }
}

// ── anomalies and savings ─────────────────────────────────────────────────

@Composable
private fun AnomalyList(anomalies: List<Anomaly>, onTransaction: ((String) -> Unit)?) {
    ReportCard {
        anomalies.forEachIndexed { i, a ->
            if (i > 0) HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // The sentence :core writes, not one assembled here: the
                    // arithmetic on screen is then literally the arithmetic
                    // that flagged the charge, and it is tested.
                    Text(a.explanation, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(3.dp))
                    Text("${a.date.format(DayMonth)} · ${Insights.money(a.excess)} above usual",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                onTransaction?.let { jump ->
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { jump(a.id) },
                        modifier = Modifier.heightIn(min = Tokens.minTouchTarget),
                    ) { Text("View") }
                }
            }
        }
    }
}

@Composable
private fun SavingsList(savings: List<Opportunity>) {
    ReportCard {
        savings.forEachIndexed { i, o ->
            if (i > 0) HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.title, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(Insights.money(o.amount), style = MoneyRow)
            }
            Spacer(Modifier.height(3.dp))
            Text(o.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (o.items.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // Joined into one wrapping line rather than a row of chips:
                // a chip row of long merchant names is the one thing on this
                // screen that could push it sideways.
                Text(o.items.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── cash flow ─────────────────────────────────────────────────────────────

@Composable
private fun CashFlowChart(flow: List<CashFlowMonth>) {
    val peak = flow.maxOf { maxOf(it.income, it.expense) }
    val running = flow.last { !it.empty }.running

    ReportCard {
        Row(
            Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            flow.forEach { f ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Bar(if (peak > 0) (f.income / peak).toFloat() else 0f, Income)
                        Bar(if (peak > 0) (f.expense / peak).toFloat() else 0f, Expense)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        f.period.month.getDisplayName(
                            java.time.format.TextStyle.SHORT, Locale.ENGLISH),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendKey("In", Income)
            LegendKey("Out", Expense)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        // The running total is the whole point of the section: months of
        // small overspend look harmless side by side and are obvious the
        // moment they are accumulated.
        Text(
            "Over these ${flow.size} months you are ${Insights.money(abs(running))} " +
            if (running >= 0) "ahead." else "behind.",
            style = MaterialTheme.typography.bodyMedium,
            color = if (running >= 0) Income else Expense,
        )
    }
}

@Composable
private fun LegendKey(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── trends and merchants ──────────────────────────────────────────────────

@Composable
private fun TrendList(trends: List<CategoryTrend>) {
    ReportCard {
        trends.forEachIndexed { i, c ->
            if (i > 0) Spacer(Modifier.height(16.dp))
            val tint = when (c.direction) {
                "rising" -> Expense
                "falling" -> Income
                else -> Primary
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (c.direction != "steady") {
                    Text(
                        (if (c.direction == "rising") "▲ " else "▼ ") + "${abs(c.changePct)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = tint,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("${Insights.money(c.avg)}/mo", style = MoneyRow)
            }
            Spacer(Modifier.height(6.dp))
            Sparkline(c.series, tint)
        }
    }
}

@Composable
private fun MerchantList(merchants: List<MerchantInsight>) {
    ReportCard {
        merchants.forEachIndexed { i, m ->
            if (i > 0) HorizontalDivider(
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(merchantSubtitle(m), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Insights.money(m.total), style = MoneyRow)
                    merchantChange(m)?.let { (text, rising) ->
                        Text(text, style = MaterialTheme.typography.bodySmall,
                            color = if (rising) Expense else Income)
                    }
                }
            }
        }
    }
}

/** Visits, average ticket and — only where :core was willing to compute one —
 *  the merchant's own monthly baseline. */
private fun merchantSubtitle(m: MerchantInsight): String = buildString {
    append("${m.visits} ")
    append(if (m.visits == 1) "visit" else "visits")
    if (m.visits > 1) append(" · ${Insights.money(m.avgTicket)} each")
    m.baseline?.let { append(" · usually ${Insights.money(it)}/mo") }
}

/**
 * The change against that baseline, and whether it is a rise.
 *
 * Past roughly a tripling :core supplies a multiple instead, because "up
 * 1265%" is a number nobody parses — so a multiple wins whenever there is
 * one, and the percentage is only ever shown at readable sizes.
 */
private fun merchantChange(m: MerchantInsight): Pair<String, Boolean>? {
    val pct = m.changePct ?: return null
    m.multiple?.let { return Pair("▲ ${it}×", true) }
    if (abs(pct) < MERCHANT_CHANGE_FLOOR) return null
    return Pair((if (pct > 0) "▲ " else "▼ ") + "${abs(pct)}%", pct > 0)
}

// ── drawing primitives ────────────────────────────────────────────────────

/**
 * One bar of a chart, sized by weight so any number of them divides the
 * width it was given. Nothing here is measured in absolute width, which is
 * what keeps a 31-day month inside the screen.
 */
@Composable
private fun RowScope.Bar(fraction: Float, color: Color) {
    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction.coerceIn(MIN_BAR_FRACTION, 1f))
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

/** A horizontal proportion bar. The track is the same colour at low alpha so
 *  the remaining share is legible without a second token. */
@Composable
private fun Meter(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.18f)),
    ) {
        val filled = fraction.coerceIn(0f, 1f)
        if (filled > 0f) {
            Box(
                Modifier.fillMaxWidth(filled).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

/**
 * A category's months as one small line, drawn from a zero baseline so a flat
 * series looks flat. Normalising to the series' own min instead would turn
 * ₹4,000, ₹4,010, ₹3,990 into a dramatic zigzag.
 */
@Composable
private fun Sparkline(series: List<Double>, color: Color) {
    val peak = series.maxOrNull() ?: 0.0
    if (series.size < 2 || peak <= 0.0) return
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val stroke = 2.dp.toPx()
        val inset = stroke / 2f
        val usableHeight = size.height - stroke
        val step = (size.width - stroke) / (series.size - 1)
        val points = series.mapIndexed { i, v ->
            Offset(
                x = inset + step * i,
                y = inset + usableHeight * (1f - (v / peak).toFloat()),
            )
        }
        for (i in 0 until points.size - 1) {
            drawLine(color, points[i], points[i + 1], strokeWidth = stroke, cap = StrokeCap.Round)
        }
        drawCircle(color, radius = stroke * 1.5f, center = points.last())
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** `#rrggbb` from the category row. Anything else falls back rather than
 *  throwing: a colour is decoration, and no decoration is worth a crash on
 *  the report screen. */
private fun hexColor(hex: String, fallback: Color): Color {
    val digits = hex.removePrefix("#")
    if (digits.length != 6) return fallback
    val rgb = digits.toLongOrNull(16) ?: return fallback
    return Color(0xFF000000L or rgb)
}
