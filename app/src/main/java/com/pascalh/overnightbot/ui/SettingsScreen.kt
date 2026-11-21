@file:OptIn(ExperimentalMaterial3Api::class)

package com.pascalh.overnightbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pascalh.overnightbot.Graph
import com.pascalh.overnightbot.core.Engine
import com.pascalh.overnightbot.core.MiniJson
import com.pascalh.overnightbot.core.Mode
import com.pascalh.overnightbot.core.Sizing
import com.pascalh.overnightbot.core.usd
import com.pascalh.overnightbot.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt
import com.pascalh.overnightbot.core.State as BotState

private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

private fun show(x: Double): String = MiniJson.stringify(Math.round(x * 10000) / 10000.0)

@Composable
fun SettingsScreen(state: BotState, reload: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = state.settings

    var mode by remember { mutableStateOf(saved.mode) }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var keyMessage by remember { mutableStateOf("") }
    var keysSaved by remember { mutableStateOf(Graph.creds.has(saved.mode)) }

    var bots by remember { mutableStateOf(if (saved.plugSharePct >= 100) "PLUG" else if (saved.plugSharePct <= 0) "RIOT" else "BOTH") }
    var plugShare by remember { mutableStateOf(if (saved.plugSharePct in 1..99) saved.plugSharePct else 50) }
    var sizing by remember { mutableStateOf(saved.sizing) }
    var total by remember { mutableStateOf(show(saved.totalUsd)) }
    var buffer by remember { mutableStateOf(show(saved.cashBufferPct * 100)) }
    var plugGreen by remember { mutableStateOf(show(saved.plugMinGreen * 100)) }
    var riotGreen by remember { mutableStateOf(show(saved.riotMinGreen * 100)) }
    var orders by remember { mutableStateOf(saved.ordersEnabled) }
    var message by remember { mutableStateOf("") }
    var confirmLive by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    val save: () -> Unit = {
        val totalUsd = number(total)
        val bufferPct = number(buffer)
        val pg = number(plugGreen)
        val rg = number(riotGreen)
        if (totalUsd == null || totalUsd <= 0) message = "Enter an amount above 0."
        else if (bufferPct == null || bufferPct < 0 || bufferPct > 50) message = "Cash buffer must be between 0 and 50%."
        else if (pg == null || rg == null || pg < 0 || rg < 0) message = "The green-day minimum must be 0 or higher."
        else {
            val share = when (bots) {
                "PLUG" -> 100
                "RIOT" -> 0
                else -> plugShare
            }
            scope.launch {
                withContext(Dispatchers.IO) {
                    Graph.update {
                        Engine.applySettings(
                            it,
                            it.settings.copy(
                                mode = mode, ordersEnabled = orders, sizing = sizing, totalUsd = totalUsd,
                                plugSharePct = share, cashBufferPct = bufferPct / 100.0,
                                plugMinGreen = pg / 100.0, riotMinGreen = rg / 100.0,
                                // Without keys for the chosen mode the bot cannot run.
                                active = it.settings.active && Graph.creds.has(mode),
                            ),
                            Instant.now().toString(),
                        )
                    }
                    Scheduler.arm(ctx.applicationContext)
                }
                message = "Saved."
                reload()
            }
        }
    }

    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text("Switch to LIVE?") },
            text = { Text("In live mode the bot sends real orders with real money in your Alpaca account. Profit counters and capital start fresh.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLive = false
                    save()
                }) { Text("Use live mode") }
            },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset capital and profit counters?") },
            text = { Text("Both bots go back to the starting amount and the realized profit/loss is set to 0. Open positions are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Graph.update { Engine.resetCapital(it) } }
                        message = "Capital reset."
                        reload()
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader("OVERNIGHT / CONFIGURATION", "Settings")

        // 01: the only part in Alpaca's yellow and white. It is about Alpaca's keys, not the app.
        PaperCard {
            Kicker("01 / Alpaca connection", AlpacaOlive)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AlpacaBadge()
                Spacer(Modifier.width(12.dp))
                Text("Alpaca API", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = AlpacaText)
            }
            Spacer(Modifier.height(14.dp))
            AlpacaSegments(listOf("Paper (test)" to (mode == Mode.PAPER), "Live (real money)" to (mode == Mode.LIVE))) { i ->
                mode = if (i == 0) Mode.PAPER else Mode.LIVE
                keysSaved = Graph.creds.has(mode)
                keyMessage = ""
            }
            Spacer(Modifier.height(14.dp))
            Text(
                if (mode == Mode.PAPER) "Alpaca's paper account does not run the closing and opening auctions. In paper mode the bot uses normal market orders 5 minutes before the close and 1 minute after the open."
                else "Live: the bot buys with a market-on-close order about 20 minutes before the close and sells in the next opening auction. Real money.",
                color = AlpacaNoticeText, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().background(AlpacaNotice).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(14.dp))
            Hint(
                if (keysSaved) "Keys for " + (if (mode == Mode.PAPER) "paper" else "live") + " mode are saved on this phone (encrypted). Enter new ones to replace them."
                else "Enter your Alpaca API Key ID and Secret Key for the selected mode. Keys are stored encrypted on this phone.",
                color = AlpacaMuted,
            )
            Spacer(Modifier.height(14.dp))
            BoxField("API Key ID *", keyId, { keyId = it }, alpaca = true, placeholder = if (keysSaved) "Saved (enter to replace)" else "Enter API Key ID")
            BoxField("Secret Key *", secret, { secret = it }, alpaca = true, secret = true, placeholder = if (keysSaved) "Saved (enter to replace)" else "Enter Secret Key")
            Spacer(Modifier.height(2.dp))
            SkewButton(
                if (keyId.isBlank() && secret.isBlank() && keysSaved) "Test connection" else "Save keys and test",
                fill = AlpacaYellow, content = AlpacaText, arrow = true, height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val m = mode
                    val id = keyId
                    val sec = secret
                    keyMessage = "Testing…"
                    scope.launch {
                        keyMessage = withContext(Dispatchers.IO) {
                            try {
                                if (id.isNotBlank() && sec.isNotBlank()) Graph.creds.set(m, id, sec)
                                val broker = Graph.broker(saved.copy(mode = m))
                                if (broker == null) "Enter both keys first."
                                else {
                                    val acc = broker.account()
                                    "Connected. Cash $" + usd(acc.cash) + ", equity $" + usd(acc.equity) + "."
                                }
                            } catch (e: Exception) {
                                "Alpaca refused the keys or could not be reached: " + (e.message ?: "error")
                            }
                        }
                        keysSaved = Graph.creds.has(m)
                        if (keysSaved) {
                            keyId = ""
                            secret = ""
                        }
                        reload()
                    }
                },
            )
            if (keyMessage.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    keyMessage, fontSize = 13.sp,
                    color = when {
                        keyMessage.startsWith("Connected") -> Color(0xFF1F7A4A)
                        keyMessage == "Testing…" || keyMessage == "Keys deleted." -> AlpacaMuted
                        else -> BadOnPaper
                    },
                )
            }
            if (keysSaved) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextAction("Delete keys", onClick = {
                        Graph.creds.clear(mode)
                        keysSaved = false
                        keyMessage = "Keys deleted."
                        reload()
                    }, color = AlpacaText)
                }
            }
        }

        // From here on: the app's own settings, in the dark angled style.
        InkCard("02 / Allocation", "Which bot gets the money") {
            InkSegments(listOf("PLUG only" to (bots == "PLUG"), "RIOT only" to (bots == "RIOT"), "Both" to (bots == "BOTH"))) { i ->
                bots = listOf("PLUG", "RIOT", "BOTH")[i]
            }
            if (bots == "BOTH") {
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("PLUG $plugShare%", fontWeight = FontWeight.Bold, color = Green, modifier = Modifier.weight(1f))
                    Text("RIOT ${100 - plugShare}%", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                SplitBar(plugShare, { plugShare = it })
                Spacer(Modifier.height(10.dp))
                Hint("Each bot trades only with its own share and keeps its own profit/loss. Moving the slider later redistributes the combined capital.")
            }
        }

        InkCard("03 / Capital", "How much") {
            InkSegments(listOf("Fixed amount" to (sizing == Sizing.FIXED), "Compounding" to (sizing == Sizing.COMPOUND))) { i ->
                sizing = if (i == 0) Sizing.FIXED else Sizing.COMPOUND
            }
            Spacer(Modifier.height(14.dp))
            BoxField(
                if (sizing == Sizing.FIXED) "Amount per night, both bots together (USD)" else "Starting capital, both bots together (USD)",
                total, { total = it }, numeric = true, suffix = "USD",
            )
            Hint(
                if (sizing == Sizing.FIXED) "Every night the bots use this amount at most, split as chosen above. Profits stay as cash."
                else "Each bot reinvests its own profits and carries its own losses. Changing this amount starts the capital fresh.",
            )
            Spacer(Modifier.height(14.dp))
            BoxField("Cash buffer (%)", buffer, { buffer = it }, numeric = true, suffix = "%")
            Hint("Share of the budget that is not invested, so a price jump before the close cannot overdraw the account.")
            Spacer(Modifier.height(6.dp))
            TextAction("Reset capital and profit counters", onClick = { confirmReset = true }, color = Green)
        }

        InkCard("04 / Strategy", "Green-day rule") {
            Hint("Minimum gain over today's open (%)")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BoxField("PLUG", plugGreen, { plugGreen = it }, numeric = true, suffix = "%", big = true, modifier = Modifier.weight(1f))
                BoxField("RIOT", riotGreen, { riotGreen = it }, numeric = true, suffix = "%", big = true, modifier = Modifier.weight(1f))
            }
            Hint("Tested settings: PLUG 0%, RIOT 1%.")
        }

        InkCard("05 / Execution") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (orders) "Send orders" else "Signals only", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                SkewToggle(orders, { orders = it })
            }
            Spacer(Modifier.height(10.dp))
            Hint("Off: the bot logs what it would do and sends nothing to Alpaca.")
        }

        Column(Modifier.padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(4.dp))
            SkewButton(
                "Save settings", arrow = true, height = 54.dp, modifier = Modifier.fillMaxWidth(),
                onClick = { if (mode == Mode.LIVE && saved.mode != Mode.LIVE) confirmLive = true else save() },
            )
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(message, color = if (message == "Saved." || message == "Capital reset.") Color(0xFF1F7A4A) else BadOnPaper, fontSize = 14.sp)
            }
            Spacer(Modifier.height(16.dp))
            Hint("Use an Alpaca account that holds nothing else in PLUG or RIOT: the bot sells the whole position of an active bot at the open.", color = PaperMuted)
            Spacer(Modifier.height(12.dp))
            Hint("The phone must be on and online about 20 minutes before the US close. Not financial advice.", color = PaperMuted)
            Spacer(Modifier.height(24.dp))
        }
    }
}
