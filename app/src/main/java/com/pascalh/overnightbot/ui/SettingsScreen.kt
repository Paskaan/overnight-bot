@file:OptIn(ExperimentalMaterial3Api::class)

package com.pascalh.overnightbot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, numeric: Boolean = false, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else if (secret) KeyboardType.Password else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    )
}

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        SectionCard("Account") {
            Row {
                Choice("Paper (test)", mode == Mode.PAPER) {
                    mode = Mode.PAPER
                    keysSaved = Graph.creds.has(Mode.PAPER)
                    keyMessage = ""
                }
                Choice("Live (real money)", mode == Mode.LIVE) {
                    mode = Mode.LIVE
                    keysSaved = Graph.creds.has(Mode.LIVE)
                    keyMessage = ""
                }
            }
            Text(
                if (mode == Mode.PAPER) "Alpaca's paper account does not run the closing and opening auctions, so in paper mode the bot uses normal market orders 5 minutes before the close and 1 minute after the open."
                else "Buys with a market-on-close order about 20 minutes before the close and sells in the next opening auction.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (keysSaved) "Keys for this mode are saved on this phone (encrypted). Enter new ones to replace them."
                else "Enter the API keys of your Alpaca " + (if (mode == Mode.PAPER) "paper" else "live") + " account.",
            )
            Spacer(Modifier.height(8.dp))
            Field("API Key ID", keyId, { keyId = it })
            Field("Secret Key", secret, { secret = it }, secret = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
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
                }) { Text(if (keyId.isBlank() && secret.isBlank() && keysSaved) "Test saved keys" else "Save keys and test") }
                if (keysSaved) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        Graph.creds.clear(mode)
                        keysSaved = false
                        keyMessage = "Keys deleted."
                        reload()
                    }) { Text("Delete keys") }
                }
            }
            if (keyMessage.isNotEmpty()) Text(keyMessage, color = Muted)
        }

        SectionCard("Which bot gets the money") {
            Row {
                Choice("PLUG only", bots == "PLUG") { bots = "PLUG" }
                Choice("RIOT only", bots == "RIOT") { bots = "RIOT" }
                Choice("Both", bots == "BOTH") { bots = "BOTH" }
            }
            if (bots == "BOTH") {
                Spacer(Modifier.height(8.dp))
                Text("PLUG $plugShare%  ·  RIOT ${100 - plugShare}%", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = plugShare.toFloat(),
                    onValueChange = { plugShare = ((it / 5f).roundToInt() * 5).coerceIn(5, 95) },
                    valueRange = 5f..95f,
                    steps = 17,
                )
                Text(
                    "Each bot trades only with its own share and keeps its own profit/loss. Moving the slider later redistributes the combined capital.",
                    color = Muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard("How much") {
            Row {
                Choice("Fixed amount", sizing == Sizing.FIXED) { sizing = Sizing.FIXED }
                Choice("Compounding", sizing == Sizing.COMPOUND) { sizing = Sizing.COMPOUND }
            }
            Spacer(Modifier.height(8.dp))
            Field(if (sizing == Sizing.FIXED) "Amount per night, both bots together (USD)" else "Starting capital, both bots together (USD)", total, { total = it }, numeric = true)
            Text(
                if (sizing == Sizing.FIXED) "Every night the bots use this amount at most, split as chosen above. Profits stay as cash."
                else "Each bot reinvests its own profits and carries its own losses. Changing this amount starts the capital fresh.",
                color = Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Field("Cash buffer (%)", buffer, { buffer = it }, numeric = true)
            Text("Share of the budget that is not invested, so a price jump before the close cannot overdraw the account.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmReset = true }) { Text("Reset capital and profit counters") }
        }

        SectionCard("Green-day rule") {
            Field("PLUG: minimum gain over today's open (%)", plugGreen, { plugGreen = it }, numeric = true)
            Field("RIOT: minimum gain over today's open (%)", riotGreen, { riotGreen = it }, numeric = true)
            Text("Tested settings: PLUG 0%, RIOT 1%.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        SectionCard("Orders") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (orders) "Send orders" else "Signals only")
                    Text("Off: the bot logs what it would do and sends nothing to Alpaca.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = orders, onCheckedChange = { orders = it })
            }
        }

        Button(
            onClick = { if (mode == Mode.LIVE && saved.mode != Mode.LIVE) confirmLive = true else save() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, color = Muted)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Use an Alpaca account that holds nothing else in PLUG or RIOT: the bot sells the whole position of an active bot at the open. " +
                "The phone must be on and online about 20 minutes before the US close. Not financial advice.",
            color = Muted, style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
    }
}
