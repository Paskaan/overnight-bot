package com.pascalh.overnightbot.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.pascalh.overnightbot.core.State as BotState

val Accent = Color(0xFFD97757)
val Good = Color(0xFF6FCF97)
val Bad = Color(0xFFEB5757)
val Muted = Color(0xFF9AA4AE)

private val Colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A1208),
    secondary = Accent,
    background = Color(0xFF101418),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF1B2229),
    onSurfaceVariant = Color(0xFFD5DBE1),
)

class MainActivity : ComponentActivity() {
    private val resumes = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Graph.init(this)
        setContent {
            MaterialTheme(colorScheme = Colors) {
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Bots") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1; reload() }, icon = { Icon(Icons.Filled.DateRange, null) }, label = { Text("Log") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
            }
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize().imePadding()) {
            when (tab) {
                0 -> DashboardScreen(state, version, reload)
                1 -> LogScreen(version)
                else -> SettingsScreen(state, reload)
            }
        }
    }
}

@Composable
fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            content()
        }
    }
}

@Composable
fun Line(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted)
        Text(value, color = color, fontWeight = FontWeight.Medium)
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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Overnight Bot", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            if (cfg.mode == Mode.LIVE) "LIVE account: real money" else "Paper account: test money",
            color = if (cfg.mode == Mode.LIVE) Accent else Muted,
        )
        Spacer(Modifier.height(12.dp))

        if (!hasKeys) {
            SectionCard("Start here") {
                Text("Open Settings and enter your Alpaca API keys for $modeName mode. Then switch the bot on.")
            }
        }

        SectionCard("") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (cfg.active) "Bot is ON" else "Bot is OFF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (!cfg.active) "No checks, no orders."
                        else if (cfg.ordersEnabled) "Next check: " + localTime(Graph.nextAlarm())
                        else "Signals only, no orders. Next check: " + localTime(Graph.nextAlarm()),
                        color = Muted,
                    )
                }
                Switch(
                    checked = cfg.active,
                    enabled = hasKeys || cfg.active,
                    onCheckedChange = { on ->
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

        SectionCard("Alpaca account ($modeName)") {
            val acc = account
            if (acc != null) {
                Line("Equity", "$" + usd(acc.equity))
                Line("Cash", "$" + usd(acc.cash))
                val day = acc.equity - acc.lastEquity
                Line("Today", signedUsd(day) + " USD", if (day >= 0) Good else Bad)
            } else {
                Text(if (accountError.isNotEmpty()) accountError else if (hasKeys) "Loading…" else "No keys saved.", color = Muted)
            }
        }

        for (sym in SYMBOLS) {
            val share = cfg.share(sym)
            val trade = state.trades.lastOrNull {
                it.symbol == sym && it.mode == modeName &&
                    it.status in setOf(TradeStatus.BUY_SENT, TradeStatus.HOLDING, TradeStatus.SELL_SENT)
            }
            val pos = positions.firstOrNull { it.symbol == sym }
            SectionCard(sym + if (share > 0) "  ·  " + Math.round(share * 100) + "% of the money" else "  ·  off") {
                Text(
                    "Buys at the close when the price is more than " + pct(cfg.minGreen(sym)) + "% above today's open. Sells at the next open.",
                    color = Muted,
                )
                Spacer(Modifier.height(6.dp))
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
                        TradeStatus.SELL_SENT -> "Sell order waiting for the open"
                        else -> if (pos != null && pos.qty > 0) "Position in account: " + Math.round(pos.qty) + " shares" else "No position"
                    },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !busy && cfg.active && hasKeys,
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
            ) { Text(if (busy) "Checking…" else "Check now") }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { reload() }) { Text("Refresh") }
        }
        if (result.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Last run: $result", color = Muted)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "\"Check now\" does the same as a scheduled run: it only trades inside the order window before the close.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Text("Not financial advice. You trade at your own risk.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Phone settings that decide whether the bot wakes up on time. */
@Composable
fun PhoneChecks(ctx: Context, version: Int) {
    val batteryOk = remember(version) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val exactOk = remember(version) { Scheduler.canExact(ctx) }
    val notifyOk = remember(version) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    if (batteryOk && exactOk && notifyOk) return
    SectionCard("Phone setup needed") {
        if (!batteryOk) {
            Text("Battery saving can stop the bot from waking up for the order window. Allow it to run without restrictions.")
            OutlinedButton(onClick = {
                open(ctx, Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Allow background running") }
        }
        if (!exactOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Text("Exact alarms are switched off. Without them the bot can miss the order window.")
            OutlinedButton(onClick = {
                open(ctx, Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            }) { Text("Allow exact alarms") }
        }
        if (!notifyOk) {
            Text("Notifications are switched off. You will not see trades, alerts or missed runs.")
            OutlinedButton(onClick = {
                open(ctx, Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }) { Text("Open notification settings") }
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

@Composable
fun LogScreen(version: Int) {
    val ctx = LocalContext.current
    val rows = remember(version) { Graph.store.readLog(400) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(
                enabled = rows.isNotEmpty(),
                onClick = {
                    val csv = LogRow.CSV_HEADER + "\n" + rows.reversed().joinToString("\n") { it.toCsv() }
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Overnight Bot log.csv")
                        .putExtra(Intent.EXTRA_TEXT, csv)
                    open(ctx, Intent.createChooser(send, "Export log as CSV"))
                },
            ) { Text("Export CSV") }
        }
        Spacer(Modifier.height(8.dp))
        if (rows.isEmpty()) {
            Text("Nothing yet. Every decision, order, fill and daily report appears here.", color = Muted)
        }
        LazyColumn {
            items(rows) { row ->
                Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                listOf(row.symbol, row.action).filter { it.isNotEmpty() }.joinToString(" · "),
                                fontWeight = FontWeight.SemiBold,
                                color = when (row.action) {
                                    "BUY", "BOUGHT", "SOLD", "SELL" -> Good
                                    "ALERT", "ERROR", "MISSED" -> Bad
                                    else -> Color.Unspecified
                                },
                            )
                            Text(row.date + " " + row.timeEt.take(5) + " ET", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(row.reason.replace(" | ", "\n"), style = MaterialTheme.typography.bodyMedium)
                        val extra = listOf(
                            if (row.qty > 0 && row.orderStatus.isNotEmpty()) "Order " + Math.round(row.qty) + " shares: " + row.orderStatus else "",
                            if (row.error.isNotEmpty()) "ERROR: " + row.error else "",
                            row.mode,
                        ).filter { it.isNotEmpty() }.joinToString("  ·  ")
                        Text(extra, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
