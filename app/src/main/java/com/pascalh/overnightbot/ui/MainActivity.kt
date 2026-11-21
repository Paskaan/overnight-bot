package com.pascalh.overnightbot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.pascalh.overnightbot.Graph
import com.pascalh.overnightbot.core.Account
import com.pascalh.overnightbot.core.Engine
import com.pascalh.overnightbot.core.LogRow
import com.pascalh.overnightbot.core.Mode
import com.pascalh.overnightbot.core.NY
import com.pascalh.overnightbot.core.Position
import com.pascalh.overnightbot.core.SYMBOLS
import com.pascalh.overnightbot.core.Sizing
import com.pascalh.overnightbot.core.TradeStatus
import com.pascalh.overnightbot.core.pct
import com.pascalh.overnightbot.core.signedUsd
import com.pascalh.overnightbot.core.usd
import com.pascalh.overnightbot.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.pascalh.overnightbot.core.State as BotState

class MainActivity : ComponentActivity() {
    private val resumes = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light page with dark icons on top; dark tab bar with light icons at the bottom.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Graph.init(this)
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                AppRoot(resumes.value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.value = resumes.value + 1
    }
}

@Composable
fun AppRoot(resumes: Int) {
    var tab by remember { mutableStateOf(0) }
    var state by remember { mutableStateOf(Graph.store.load()) }
    var version by remember { mutableStateOf(0) }
    val reload = {
        state = Graph.store.load()
        version = version + 1
    }
    LaunchedEffect(resumes) { reload() }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Box(Modifier.weight(1f).fillMaxWidth().imePadding()) {
            when (tab) {
                0 -> DashboardScreen(state, version, reload)
                1 -> LogScreen(version)
                else -> SettingsScreen(state, reload)
            }
        }
        BottomTabs(tab) { t ->
            tab = t
            if (t == 1) reload()
        }
    }
}

private fun localTime(ms: Long): String {
    if (ms <= 0L) return "not set"
    val at = Instant.ofEpochMilli(ms)
    val local = at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.US))
    val ny = at.atZone(NY).format(DateTimeFormatter.ofPattern("HH:mm", Locale.US))
    return "$local ($ny New York)"
}

@Composable
private fun ModeBadge(mode: Mode) {
    if (mode == Mode.LIVE) Badge("LIVE", Bad) else Badge("PAPER", Green)
}

// ========================================================================================= Bots

@Composable
fun DashboardScreen(state: BotState, version: Int, reload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = state.settings
    val modeName = cfg.mode.name.lowercase()
    val hasKeys = remember(version, cfg.mode) { Graph.creds.has(cfg.mode) }
    var busy by remember { mutableStateOf(false) }
    var result by remember(version) { mutableStateOf(Graph.lastResult()) }
    var account by remember { mutableStateOf<Account?>(null) }
    var positions by remember { mutableStateOf<List<Position>>(emptyList()) }
    var accountError by remember { mutableStateOf("") }

    val refreshAccount: () -> Unit = {
        scope.launch {
            try {
                val pair = withContext(Dispatchers.IO) {
                    val broker = Graph.broker(cfg) ?: throw IllegalStateException("No API keys saved for $modeName mode")
                    broker.account() to broker.positions()
                }
                account = pair.first
                positions = pair.second
                accountError = ""
            } catch (e: Exception) {
                account = null
                accountError = e.message ?: "Could not reach Alpaca"
            }
        }
    }
    LaunchedEffect(version, cfg.mode, hasKeys) { if (hasKeys) refreshAccount() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("OVERNIGHT / TRADING BOT", "Your bots") { ModeBadge(cfg.mode) }

        if (!hasKeys) {
            PaperCard {
                Kicker("Start here", AlpacaOlive)
                Spacer(Modifier.height(10.dp))
                Text("Open Settings and enter your Alpaca API keys for $modeName mode. Then switch the bot on.", fontSize = 15.sp)
            }
        }

        InkCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (cfg.active) "Bot is ON" else "Bot is OFF", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (cfg.active) Green else OnInk)
                    Spacer(Modifier.height(4.dp))
                    Hint(
                        if (!cfg.active) "No checks, no orders."
                        else if (cfg.ordersEnabled) "Next check: " + localTime(Graph.nextAlarm())
                        else "Signals only, no orders. Next check: " + localTime(Graph.nextAlarm()),
                    )
                }
                Spacer(Modifier.width(12.dp))
                SkewToggle(
                    checked = cfg.active,
                    enabled = hasKeys || cfg.active,
                    onChange = { on ->
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                Graph.update { Engine.applySettings(it, it.settings.copy(active = on), Instant.now().toString()) }
                                Scheduler.arm(ctx.applicationContext)
                            }
                            reload()
                        }
                    },
                )
            }
        }

        PhoneChecks(ctx, version)

        InkCard("01 / Alpaca account") {
            val acc = account
            if (acc != null) {
                Hint("Total equity", fontSizeLarge = true)
                Text("$" + usd(acc.equity), fontSize = 42.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
                InkDivider()
                Line("Available cash", "$" + usd(acc.cash))
                val day = acc.equity - acc.lastEquity
                Line("Today", signedUsd(day) + " USD", if (day >= 0) Good else Bad)
            } else {
                Hint(if (accountError.isNotEmpty()) accountError else if (hasKeys) "Loading…" else "No keys saved.")
            }
        }

        for (sym in SYMBOLS) {
            val share = cfg.share(sym)
            val trade = state.trades.lastOrNull {
                it.symbol == sym && it.mode == modeName &&
                    it.status in setOf(TradeStatus.BUY_SENT, TradeStatus.HOLDING, TradeStatus.SELL_SENT)
            }
            val pos = positions.firstOrNull { it.symbol == sym }
            InkCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sym, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (share > 0) OnInk else OnInkMuted, modifier = Modifier.weight(1f))
                    Kicker(if (share > 0) "" + Math.round(share * 100) + "% allocation" else "off", if (share > 0) Gold else OnInkMuted)
                }
                Spacer(Modifier.height(12.dp))
                Hint("Buys at the close when the price is more than " + pct(cfg.minGreen(sym)) + "% above today's open. Sells at the next open.", fontSizeLarge = true)
                InkDivider()
                if (share > 0) {
                    if (cfg.sizing == Sizing.COMPOUND) Line("Capital (compounding)", "$" + usd(state.capital(sym)))
                    else Line("Per night (fixed)", "$" + usd(cfg.totalUsd * share))
                }
                val pnl = state.pnl(sym)
                Line("Realized profit/loss", signedUsd(pnl) + " USD", if (pnl >= 0) Good else Bad)
                Line(
                    "Now",
                    when (trade?.status) {
                        TradeStatus.BUY_SENT -> "Buy order sent"
                        TradeStatus.HOLDING -> "Holding " + Math.round(trade?.qty ?: 0.0) + " shares"
                        TradeStatus.SELL_SENT -> "Sell waiting for the open"
                        else -> if (pos != null && pos.qty > 0) "In account: " + Math.round(pos.qty) + " shares" else "No position"
                    },
                )
            }
        }

        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkewButton(
                    if (busy) "Checking…" else "Check now", arrow = !busy,
                    enabled = !busy && cfg.active && hasKeys,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        busy = true
                        scope.launch {
                            val text = withContext(Dispatchers.IO) {
                                try {
                                    Graph.runner.tick()
                                } catch (e: Exception) {
                                    "No connection to Alpaca: " + (e.message ?: "network error")
                                }
                            }
                            withContext(Dispatchers.IO) {
                                Graph.setLastResult(text)
                                Scheduler.arm(ctx.applicationContext)
                            }
                            result = text
                            busy = false
                            reload()
                        }
                    },
                )
                SkewButton("Refresh", onClick = { reload() }, fill = Stone, modifier = Modifier.weight(1f))
            }
            if (result.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Hint("Last run: $result", color = Ink)
            }
            Spacer(Modifier.height(12.dp))
            Hint("\"Check now\" does the same as a scheduled run: it only trades inside the order window before the close.", color = PaperMuted)
            Spacer(Modifier.height(10.dp))
            Hint("Not financial advice. You trade at your own risk.", color = PaperMuted)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Larger variant of [Hint] for descriptions inside cards. */
@Composable
private fun Hint(text: String, fontSizeLarge: Boolean) {
    Text(text, color = OnInkMuted, fontSize = if (fontSizeLarge) 15.sp else 13.sp, lineHeight = if (fontSizeLarge) 21.sp else 18.sp)
}

/** Phone settings that decide whether the bot wakes up on time. */
@Composable
fun PhoneChecks(ctx: Context, version: Int) {
    val batteryOk = remember(version) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val exactOk = remember(version) { Scheduler.canExact(ctx) }
    val notifyOk = remember(version) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    if (batteryOk && exactOk && notifyOk) return
    PaperCard {
        Kicker("Phone setup needed", BadOnPaper)
        Spacer(Modifier.height(10.dp))
        if (!batteryOk) {
            Text("Battery saving can stop the bot from waking up for the order window. Allow it to run without restrictions.", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            SkewButton("Allow background running", fill = Stone, height = 42.dp, onClick = {
                open(ctx, Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            })
            Spacer(Modifier.height(14.dp))
        }
        if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text("Exact alarms are switched off. Without them the bot can miss the order window.", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            SkewButton("Allow exact alarms", fill = Stone, height = 42.dp, onClick = {
                open(ctx, Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            })
            Spacer(Modifier.height(14.dp))
        }
        if (!notifyOk) {
            Text("Notifications are switched off. You will not see trades, alerts or missed runs.", fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            SkewButton("Open notification settings", fill = Stone, height = 42.dp, onClick = {
                open(ctx, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            })
        }
    }
}

fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Exception) {
        }
    }
}

// ========================================================================================== Log

private enum class Kind { TRADE, SKIP, ALERT, REPORT, OTHER }

private fun kindOf(row: LogRow): Kind = when (row.action) {
    "BUY", "BOUGHT", "SELL", "SOLD" -> Kind.TRADE
    "SKIP" -> Kind.SKIP
    "ALERT", "ERROR", "MISSED" -> Kind.ALERT
    "REPORT" -> Kind.REPORT
    else -> if (row.error.isNotEmpty()) Kind.ALERT else Kind.OTHER
}

private fun kindColor(k: Kind): Color = when (k) {
    Kind.TRADE -> Green
    Kind.ALERT -> Bad
    Kind.REPORT -> Gold
    Kind.SKIP -> OnInkMuted
    Kind.OTHER -> OnInk
}

private fun prettyDate(iso: String): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)).uppercase()
} catch (e: Exception) {
    iso
}

@Composable
fun LogScreen(version: Int) {
    val ctx = LocalContext.current
    val rows = remember(version) { Graph.store.readLog(400) }
    var filter by remember { mutableStateOf(0) } // 0 all, 1 trades, 2 skips, 3 alerts
    val shown = remember(rows, filter) {
        when (filter) {
            1 -> rows.filter { kindOf(it) == Kind.TRADE }
            2 -> rows.filter { kindOf(it) == Kind.SKIP }
            3 -> rows.filter { kindOf(it) == Kind.ALERT }
            else -> rows
        }
    }
    // Rows come newest first; keep that order and group by trading day.
    val days = remember(shown) { shown.groupBy { it.date }.toList() }
    val trades = rows.count { it.action == "SOLD" }
    val skips = rows.count { kindOf(it) == Kind.SKIP }
    val alerts = rows.count { kindOf(it) == Kind.ALERT }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            AppHeader("OVERNIGHT / ACTIVITY", "Log") {
                SkewButton(
                    "CSV", arrow = true, fill = Stone, height = 38.dp, enabled = rows.isNotEmpty(),
                    onClick = {
                        val csv = LogRow.CSV_HEADER + "\n" + rows.reversed().joinToString("\n") { it.toCsv() }
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "Overnight Bot log.csv")
                            .putExtra(Intent.EXTRA_TEXT, csv)
                        open(ctx, Intent.createChooser(send, "Export log as CSV"))
                    },
                )
            }
        }
        item {
            InkCard("01 / Last ${rows.size} entries") {
                Row(Modifier.fillMaxWidth()) {
                    Stat("Round trips", trades.toString(), Green, Modifier.weight(1f))
                    Stat("Skipped", skips.toString(), OnInk, Modifier.weight(1f))
                    Stat("Alerts", alerts.toString(), if (alerts > 0) Bad else OnInk, Modifier.weight(1f))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("All", "Trades", "Skips", "Alerts").forEachIndexed { i, label ->
                    Segment(label, filter == i, { filter = i }, Ink, OnInk, Stone, PaperMuted)
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                PaperCard {
                    Text(
                        if (rows.isEmpty()) "Nothing yet. Every decision, order, fill and daily report appears here."
                        else "No entries of this kind yet.",
                        fontSize = 15.sp,
                    )
                }
            }
        }
        items(days, key = { it.first }) { (date, dayRows) ->
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(prettyDate(date), fontFamily = Mono, fontSize = 11.sp, color = Ink)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(Color(0xFFD9D6CE)))
                }
                InkCard {
                    dayRows.forEachIndexed { i, row ->
                        if (i > 0) InkDivider()
                        LogEntry(row)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 12.sp, color = OnInkMuted)
    }
}

@Composable
private fun LogEntry(row: LogRow) {
    val kind = kindOf(row)
    val color = kindColor(kind)
    Row {
        Column(Modifier.width(52.dp)) {
            Text(row.timeEt.take(5), fontFamily = Mono, fontSize = 13.sp, color = OnInk)
            Text("ET", fontFamily = Mono, fontSize = 10.sp, color = OnInkMuted)
        }
        Box(Modifier.padding(top = 5.dp, end = 12.dp).size(width = 3.dp, height = 14.dp).background(color))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    listOf(row.symbol, row.action).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = color, modifier = Modifier.weight(1f),
                )
                if (row.mode.isNotEmpty()) Text(row.mode.uppercase(), fontFamily = Mono, fontSize = 10.sp, color = OnInkMuted)
            }
            if (row.reason.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(row.reason.replace(" | ", "\n"), fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFD9D5CC))
            }
            val facts = listOf(
                if (row.price > 0) "price $" + usd(row.price) else "",
                if (row.qty > 0 && row.orderStatus.isNotEmpty()) Math.round(row.qty).toString() + " sh · " + row.orderStatus else "",
            ).filter { it.isNotEmpty() }
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(facts.joinToString("  ·  "), fontFamily = Mono, fontSize = 11.sp, color = OnInkMuted)
            }
            if (row.error.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("ERROR: " + row.error, fontSize = 13.sp, color = Bad)
            }
        }
    }
}
