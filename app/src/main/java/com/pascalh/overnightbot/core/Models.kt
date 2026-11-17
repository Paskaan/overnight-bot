package com.pascalh.overnightbot.core

import java.time.Instant
import java.time.ZoneId

val NY: ZoneId = ZoneId.of("America/New_York")
val SYMBOLS = listOf("PLUG", "RIOT")

enum class Mode { PAPER, LIVE }
enum class Sizing { FIXED, COMPOUND }

data class Settings(
    /** Master switch. Nothing is checked or traded while false. */
    val active: Boolean = false,
    val mode: Mode = Mode.PAPER,
    /** false = log signals only, send no orders. */
    val ordersEnabled: Boolean = true,
    val sizing: Sizing = Sizing.FIXED,
    /** FIXED: amount per night for both bots together. COMPOUND: starting capital for both bots together. */
    val totalUsd: Double = 100.0,
    /** Share of the money that goes to PLUG, 0..100. RIOT gets the rest. 100 = PLUG only, 0 = RIOT only. */
    val plugSharePct: Int = 100,
    val cashBufferPct: Double = 0.05,
    /** Minimum gain over today's open to count as a green day (0.01 = 1%). */
    val plugMinGreen: Double = 0.0,
    val riotMinGreen: Double = 0.01,
) {
    fun share(symbol: String): Double {
        val plug = plugSharePct.coerceIn(0, 100) / 100.0
        return if (symbol == "PLUG") plug else 1.0 - plug
    }

    fun enabled(symbol: String): Boolean = share(symbol) > 0.0
    fun enabledSymbols(): List<String> = SYMBOLS.filter { enabled(it) }
    fun minGreen(symbol: String): Double = if (symbol == "PLUG") plugMinGreen else riotMinGreen

    fun toJson(): JObj = mapOf(
        "active" to active, "mode" to mode.name, "ordersEnabled" to ordersEnabled,
        "sizing" to sizing.name, "totalUsd" to totalUsd, "plugSharePct" to plugSharePct,
        "cashBufferPct" to cashBufferPct, "plugMinGreen" to plugMinGreen, "riotMinGreen" to riotMinGreen,
    )

    companion object {
        fun fromJson(j: JObj): Settings {
            val def = Settings()
            if (j.isEmpty()) return def
            return Settings(
                active = j.b("active"),
                mode = if (j.s("mode") == "LIVE") Mode.LIVE else Mode.PAPER,
                ordersEnabled = if (j.containsKey("ordersEnabled")) j.b("ordersEnabled") else true,
                sizing = if (j.s("sizing") == "COMPOUND") Sizing.COMPOUND else Sizing.FIXED,
                totalUsd = if (j.containsKey("totalUsd")) j.d("totalUsd") else def.totalUsd,
                plugSharePct = if (j.containsKey("plugSharePct")) j.d("plugSharePct").toInt() else def.plugSharePct,
                cashBufferPct = if (j.containsKey("cashBufferPct")) j.d("cashBufferPct") else def.cashBufferPct,
                plugMinGreen = if (j.containsKey("plugMinGreen")) j.d("plugMinGreen") else def.plugMinGreen,
                riotMinGreen = if (j.containsKey("riotMinGreen")) j.d("riotMinGreen") else def.riotMinGreen,
            )
        }
    }
}

object TradeStatus {
    const val BUY_SENT = "BUY_SENT"
    const val HOLDING = "HOLDING"
    const val SELL_SENT = "SELL_SENT"
    const val CLOSED = "CLOSED"
    const val FAILED = "FAILED"
}

data class Trade(
    val id: String,
    val symbol: String,
    val mode: String,
    val buyDate: String,
    val qty: Double,
    val buyOrderId: String = "",
    val buyPx: Double = 0.0,
    val sellOrderId: String = "",
    val sellPx: Double = 0.0,
    val pnl: Double = 0.0,
    val status: String = TradeStatus.BUY_SENT,
) {
    fun toJson(): JObj = mapOf(
        "id" to id, "symbol" to symbol, "mode" to mode, "buyDate" to buyDate, "qty" to qty,
        "buyOrderId" to buyOrderId, "buyPx" to buyPx, "sellOrderId" to sellOrderId,
        "sellPx" to sellPx, "pnl" to pnl, "status" to status,
    )

    companion object {
        fun fromJson(j: JObj) = Trade(
            id = j.s("id"), symbol = j.s("symbol"), mode = j.s("mode"), buyDate = j.s("buyDate"),
            qty = j.d("qty"), buyOrderId = j.s("buyOrderId"), buyPx = j.d("buyPx"),
            sellOrderId = j.s("sellOrderId"), sellPx = j.d("sellPx"), pnl = j.d("pnl"), status = j.s("status"),
        )
    }
}

/** One trading session from the Alpaca calendar. Times are New York wall-clock "HH:mm". */
data class CalDay(val date: String, val open: String, val close: String)

data class State(
    val settings: Settings = Settings(),
    /** Compounding capital per bot. */
    val ledger: Map<String, Double> = emptyMap(),
    /** Realized profit/loss per bot since the last reset. */
    val realized: Map<String, Double> = emptyMap(),
    val trades: List<Trade> = emptyList(),
    /** Markers for work already done, e.g. "close:PLUG" -> "2026-11-17". */
    val done: Map<String, String> = emptyMap(),
    val calendar: List<CalDay> = emptyList(),
) {
    fun capital(symbol: String): Double = ledger[symbol] ?: 0.0
    fun pnl(symbol: String): Double = realized[symbol] ?: 0.0
    fun mark(key: String, value: String): State = copy(done = done + (key to value))

    fun toJson(): JObj = mapOf(
        "settings" to settings.toJson(),
        "ledger" to ledger,
        "realized" to realized,
        "trades" to trades.map { it.toJson() },
        "done" to done,
        "calendar" to calendar.map { mapOf("date" to it.date, "open" to it.open, "close" to it.close) },
    )

    companion object {
        fun fromJson(j: JObj): State = State(
            settings = Settings.fromJson(j.o("settings")),
            ledger = j.o("ledger").mapValues { (it.value as? Number)?.toDouble() ?: 0.0 },
            realized = j.o("realized").mapValues { (it.value as? Number)?.toDouble() ?: 0.0 },
            trades = j.a("trades").map { Trade.fromJson(it.asObj()) },
            done = j.o("done").mapValues { it.value?.toString() ?: "" },
            calendar = j.a("calendar").map { it.asObj() }.map { CalDay(it.s("date"), it.s("open"), it.s("close")) },
        )
    }
}

data class LogRow(
    val ts: Long,
    val date: String,
    val timeEt: String,
    val mode: String,
    val symbol: String,
    /** CLOSE, OPEN, FILL, REPORT, ERROR */
    val phase: String,
    val action: String,
    val reason: String,
    val price: Double = 0.0,
    val todayOpen: Double = 0.0,
    val intradayPct: Double = 0.0,
    val qty: Double = 0.0,
    val orderStatus: String = "",
    val error: String = "",
) {
    fun toJson(): JObj = mapOf(
        "ts" to ts, "date" to date, "timeEt" to timeEt, "mode" to mode, "symbol" to symbol,
        "phase" to phase, "action" to action, "reason" to reason, "price" to price,
        "todayOpen" to todayOpen, "intradayPct" to intradayPct, "qty" to qty,
        "orderStatus" to orderStatus, "error" to error,
    )

    companion object {
        const val CSV_HEADER = "date,time_et,mode,symbol,phase,action,reason,price,today_open,intraday_pct,qty,order_status,error"

        fun fromJson(j: JObj) = LogRow(
            ts = j.d("ts").toLong(), date = j.s("date"), timeEt = j.s("timeEt"), mode = j.s("mode"),
            symbol = j.s("symbol"), phase = j.s("phase"), action = j.s("action"), reason = j.s("reason"),
            price = j.d("price"), todayOpen = j.d("todayOpen"), intradayPct = j.d("intradayPct"),
            qty = j.d("qty"), orderStatus = j.s("orderStatus"), error = j.s("error"),
        )
    }

    fun toCsv(): String {
        fun q(s: String) = "\"" + s.replace("\"", "\"\"").replace("\n", " ") + "\""
        return listOf(
            date, timeEt, mode, symbol, phase, action, q(reason), price.toString(), todayOpen.toString(),
            intradayPct.toString(), MiniJson.stringify(qty), orderStatus, q(error),
        ).joinToString(",")
    }
}

// ---- Broker data ----

data class Clock(val timestamp: Instant, val isOpen: Boolean, val nextOpen: Instant, val nextClose: Instant)

data class Account(
    val cash: Double,
    val buyingPower: Double,
    val equity: Double,
    val lastEquity: Double,
    val blocked: Boolean,
)

data class Position(val symbol: String, val qty: Double, val avgEntry: Double, val current: Double, val marketValue: Double)

data class Order(
    val id: String,
    val symbol: String,
    val side: String,
    val status: String,
    val filledQty: Double,
    val filledAvgPx: Double,
)

data class Bar(val open: Double, val close: Double, val time: Instant?)

data class Snapshot(val lastTrade: Double, val dailyBar: Bar?, val prevClose: Double)

/** Alpaca answered, but refused the request (wrong keys, rejected order, ...). */
class BrokerException(val code: Int, message: String) : Exception(message)

interface Broker {
    fun clock(): Clock
    fun calendar(start: String, end: String): List<CalDay>
    fun account(): Account
    fun positions(): List<Position>
    fun openOrders(symbol: String): List<Order>
    fun order(id: String): Order
    fun snapshot(symbol: String, feed: String): Snapshot?
    fun submit(symbol: String, qty: Int, side: String, tif: String, clientOrderId: String): Order
    fun closedOrdersSince(symbols: List<String>, afterIso: String): List<Order>
}

interface Notifier {
    /** important = make a sound; otherwise deliver silently. */
    fun notify(title: String, text: String, important: Boolean)
}

interface Store {
    fun load(): State
    fun save(state: State)
    fun appendLog(row: LogRow)
    fun readLog(limit: Int): List<LogRow>
}
