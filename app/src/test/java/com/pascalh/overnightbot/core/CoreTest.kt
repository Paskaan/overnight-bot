package com.pascalh.overnightbot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** A simulated Alpaca account with closing and opening auctions. */
class FakeBroker : Broker {
    data class Sim(val id: String, val symbol: String, val side: String, val qty: Int, val tif: String, val coid: String,
                   var status: String = "accepted", var filledQty: Double = 0.0, var px: Double = 0.0)

    var now: ZonedDateTime = at("2026-11-17T15:40")
    var cash = 1000.0
    var lastEquity = 1000.0
    var offline = false
    var holidays = setOf<String>()
    val open = HashMap<String, Double>()
    val last = HashMap<String, Double>()
    val pos = HashMap<String, Pair<Double, Double>>() // qty, avg entry
    val orders = ArrayList<Sim>()
    var auctionsFill = true

    companion object {
        fun at(s: String): ZonedDateTime = LocalDateTime.parse(s).atZone(NY)
    }

    fun time(s: String) {
        now = at(s)
    }

    private fun trading(d: LocalDate) =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY && d.toString() !in holidays

    private fun check() {
        if (offline) throw IOException("offline")
    }

    private fun nextSession(from: ZonedDateTime, t: LocalTime): ZonedDateTime {
        var d = from.toLocalDate()
        while (true) {
            val cand = ZonedDateTime.of(d, t, NY)
            if (trading(d) && cand.isAfter(from)) return cand
            d = d.plusDays(1)
        }
    }

    private fun isOpen(): Boolean {
        val t = now.toLocalTime()
        return trading(now.toLocalDate()) && !t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))
    }

    override fun clock(): Clock {
        check()
        return Clock(now.toInstant(), isOpen(), nextSession(now, LocalTime.of(9, 30)).toInstant(), nextSession(now, LocalTime.of(16, 0)).toInstant())
    }

    override fun calendar(start: String, end: String): List<CalDay> {
        check()
        val out = ArrayList<CalDay>()
        var d = LocalDate.parse(start)
        while (!d.isAfter(LocalDate.parse(end))) {
            if (trading(d)) out.add(CalDay(d.toString(), "09:30", "16:00"))
            d = d.plusDays(1)
        }
        return out
    }

    private fun equity() = cash + pos.entries.sumOf { it.value.first * (last[it.key] ?: 0.0) }

    override fun account(): Account {
        check()
        return Account(cash, cash, equity(), lastEquity, false)
    }

    override fun positions(): List<Position> {
        check()
        return pos.filter { it.value.first > 0 }.map {
            Position(it.key, it.value.first, it.value.second, last[it.key] ?: 0.0, it.value.first * (last[it.key] ?: 0.0))
        }
    }

    private fun toOrder(s: Sim) = Order(s.id, s.symbol, s.side, s.status, s.filledQty, s.px)

    override fun openOrders(symbol: String): List<Order> {
        check()
        return orders.filter { it.symbol == symbol && it.status == "accepted" }.map { toOrder(it) }
    }

    override fun order(id: String): Order {
        check()
        return toOrder(orders.firstOrNull { it.id == id } ?: throw BrokerException(404, "order not found"))
    }

    override fun snapshot(symbol: String, feed: String): Snapshot? {
        check()
        val o = open[symbol] ?: return null
        val bar = Bar(o, last[symbol] ?: o, now.toLocalDate().atTime(4, 0).atZone(NY).toInstant())
        return Snapshot(last[symbol] ?: 0.0, bar, o)
    }

    override fun submit(symbol: String, qty: Int, side: String, tif: String, clientOrderId: String): Order {
        check()
        if (orders.any { it.coid == clientOrderId }) throw BrokerException(422, "client_order_id must be unique")
        val t = now.toLocalTime()
        val tradingDay = trading(now.toLocalDate())
        if (tif == "opg" && tradingDay && !t.isBefore(LocalTime.of(9, 28)) && t.isBefore(LocalTime.of(19, 0)))
            throw BrokerException(422, "opg orders must be submitted after 7:00pm and before 9:28am")
        if (tif == "cls" && (!isOpen() || !t.isBefore(LocalTime.of(15, 50))))
            throw BrokerException(422, "cls orders must be submitted before 3:50pm")
        if (side == "buy" && qty * (last[symbol] ?: 0.0) > cash) throw BrokerException(403, "insufficient buying power")
        val s = Sim("o${orders.size + 1}", symbol, side, qty, tif, clientOrderId)
        orders.add(s)
        if (tif == "day" && isOpen()) fill(s, last[symbol] ?: 0.0)
        return toOrder(s)
    }

    override fun closedOrdersSince(symbols: List<String>, afterIso: String): List<Order> {
        check()
        return orders.filter { it.status != "accepted" }.map { toOrder(it) }
    }

    private fun fill(s: Sim, px: Double) {
        s.status = "filled"
        s.filledQty = s.qty.toDouble()
        s.px = px
        val cur = pos[s.symbol] ?: (0.0 to 0.0)
        if (s.side == "buy") {
            cash -= s.qty * px
            pos[s.symbol] = (cur.first + s.qty) to px
        } else {
            cash += s.qty * px
            pos[s.symbol] = (cur.first - s.qty) to cur.second
        }
    }

    /** Closing or opening auction at the given prices. */
    fun auction(tif: String, prices: Map<String, Double>) {
        for ((k, v) in prices) last[k] = v
        for (s in orders.filter { it.status == "accepted" && it.tif == tif }) {
            if (auctionsFill) fill(s, last[s.symbol] ?: 0.0) else s.status = "canceled"
        }
        if (tif == "cls") lastEquity = equity()
    }
}

class MemNotifier : Notifier {
    val all = ArrayList<Triple<String, String, Boolean>>()
    override fun notify(title: String, text: String, important: Boolean) {
        all.add(Triple(title, text, important))
    }

    fun texts() = all.joinToString("\n---\n") { it.first + "\n" + it.second }
}

class MemStore : Store {
    var state = State()
    val rows = ArrayList<LogRow>()
    override fun load() = state
    override fun save(state: State) {
        this.state = state
    }

    override fun appendLog(row: LogRow) {
        rows.add(row)
    }

    override fun readLog(limit: Int) = rows.takeLast(limit).reversed()
}

class CoreTest {
    private val broker = FakeBroker()
    private val store = MemStore()
    private val notes = MemNotifier()
    private val runner = Runner(store, { broker }, notes)

    private fun setup(s: Settings, sinceIso: String = "2026-11-01T00:00:00Z") {
        store.state = Engine.applySettings(State(), s, sinceIso)
    }

    private fun live(share: Int, sizing: Sizing = Sizing.FIXED, total: Double = 200.0) =
        Settings(active = true, mode = Mode.LIVE, sizing = sizing, totalUsd = total, plugSharePct = share)

    private fun actions(phase: String) = store.rows.filter { it.phase == phase }.map { it.symbol + ":" + it.action }

    // ---------- JSON ----------

    @Test
    fun jsonRoundTrip() {
        val text = """{"a":1,"b":"x\"y\n","c":[true,false,null,-2.5e1],"d":{"e":"1.25"},"u":"é"}"""
        val j = MiniJson.parse(text).asObj()
        assertEquals(1.0, j.d("a"), 0.0)
        assertEquals("x\"y\n", j.s("b"))
        assertEquals(listOf(true, false, null, -25.0), j.a("c"))
        assertEquals(1.25, j.o("d").d("e"), 0.0)
        assertEquals("é", j.s("u"))
        assertEquals(j, MiniJson.parse(MiniJson.stringify(j)))
        assertEquals("{\"n\":3,\"x\":2.5}", MiniJson.stringify(mapOf("n" to 3.0, "x" to 2.5)))
    }

    @Test
    fun stateRoundTrip() {
        val st = State(
            settings = Settings(active = true, mode = Mode.LIVE, sizing = Sizing.COMPOUND, plugSharePct = 60),
            ledger = mapOf("PLUG" to 60.5, "RIOT" to 40.0), realized = mapOf("PLUG" to -1.25),
            trades = listOf(Trade("t1", "PLUG", "live", "2026-11-17", 43.0, "o1", 2.31)),
            done = mapOf("report" to "2026-11-17"), calendar = listOf(CalDay("2026-11-17", "09:30", "16:00")),
        )
        assertEquals(st, State.fromJson(MiniJson.parse(MiniJson.stringify(st.toJson())).asObj()))
        val dir = File(System.getProperty("java.io.tmpdir"), "obtest-" + System.nanoTime())
        val fs = FileStore(dir)
        assertEquals(State(), fs.load())
        fs.save(st)
        assertEquals(st, fs.load())
        fs.appendLog(LogRow(1, "2026-11-17", "15:40:00", "live", "PLUG", "CLOSE", "BUY", "a \"b\", c"))
        fs.appendLog(LogRow(2, "2026-11-17", "15:40:01", "live", "RIOT", "CLOSE", "NONE", "red"))
        assertEquals(listOf("RIOT", "PLUG"), fs.readLog(10).map { it.symbol })
        assertTrue(fs.readLog(10)[1].toCsv().contains("\"a \"\"b\"\", c\""))
        dir.deleteRecursively()
    }

    // ---------- Alpaca parsing ----------

    @Test
    fun alpacaParsing() {
        val calls = ArrayList<String>()
        val http = object : Http {
            override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
                calls.add("$method $url ${body ?: ""}")
                assertEquals("KEY", headers["APCA-API-KEY-ID"])
                return when {
                    url.endsWith("/v2/clock") -> HttpResponse(200, """{"timestamp":"2026-11-17T15:40:00.123456789-05:00","is_open":true,"next_open":"2026-11-18T09:30:00-05:00","next_close":"2026-11-17T16:00:00-05:00"}""")
                    url.contains("/snapshots") -> HttpResponse(200, """{"PLUG":{"latestTrade":{"p":2.31,"t":"2026-11-17T20:39:59Z"},"dailyBar":{"o":2.25,"c":2.3,"t":"2026-11-17T05:00:00Z"},"prevDailyBar":{"c":2.2}}}""")
                    url.endsWith("/v2/account") -> HttpResponse(200, """{"cash":"512.34","buying_power":"512.34","equity":"600","last_equity":"590.5","trading_blocked":false,"account_blocked":false}""")
                    url.endsWith("/v2/positions") -> HttpResponse(200, """[{"symbol":"RIOT","qty":"7","avg_entry_price":"14.2","current_price":"14.5","market_value":"101.5"}]""")
                    method == "POST" -> HttpResponse(422, """{"code":42210000,"message":"cls orders must be submitted before 3:50pm"}""")
                    else -> HttpResponse(401, "unauthorized")
                }
            }
        }
        val b = AlpacaBroker(http, "KEY", "SECRET", Mode.LIVE, retryWaitMs = 0)
        val c = b.clock()
        assertTrue(c.isOpen)
        assertEquals(20L, java.time.Duration.between(c.timestamp, c.nextClose).toMinutes() + 1)
        val s = b.snapshot("PLUG", "iex")!!
        assertEquals(2.31, s.lastTrade, 0.0)
        assertEquals(2.25, Engine.todayOpen("2026-11-17", null, s), 0.0)
        assertEquals(0.0, Engine.todayOpen("2026-11-18", null, s), 0.0)
        assertNull(b.snapshot("RIOT", "iex"))
        assertEquals(512.34, b.account().cash, 0.0)
        assertEquals(7.0, b.positions()[0].qty, 0.0)
        try {
            b.submit("PLUG", 43, "buy", "cls", "x")
            assertTrue(false)
        } catch (e: BrokerException) {
            assertTrue(e.message!!.contains("3:50pm"))
        }
        assertTrue(calls.last().contains("\"qty\":\"43\"") && calls.last().contains("\"time_in_force\":\"cls\""))
        assertTrue(calls[0].startsWith("GET https://api.alpaca.markets/v2/clock"))
        try {
            b.openOrders("PLUG")
            assertTrue(false)
        } catch (e: BrokerException) {
            assertEquals(401, e.code)
        }
    }

    // ---------- rules ----------

    private fun input(symbol: String, minGreen: Double, open: Double, price: Double, base: Double = 100.0) = Engine.CloseInput(
        symbol, minGreen, false, true, 20.0, 10.0, 30.0, 0, 0.0, price, open, base, 1000.0, 1000.0, 0.05,
    )

    @Test
    fun closeRules() {
        assertEquals(41, Engine.decideClose(input("PLUG", 0.0, 2.25, 2.31)).qty) // 95 / 2.31
        assertEquals("NONE", Engine.decideClose(input("PLUG", 0.0, 2.31, 2.31)).action)
        assertEquals("NONE", Engine.decideClose(input("PLUG", 0.0, 2.40, 2.31)).action)
        // RIOT needs more than +1%
        assertEquals("NONE", Engine.decideClose(input("RIOT", 0.01, 14.0, 14.07)).action)
        assertTrue(Engine.decideClose(input("RIOT", 0.01, 14.0, 14.07)).reason.startsWith("Not green enough"))
        assertEquals("BUY", Engine.decideClose(input("RIOT", 0.01, 14.0, 14.21)).action)
        assertEquals(6, Engine.decideClose(input("RIOT", 0.01, 14.0, 14.21)).qty)
        assertEquals("SKIP", Engine.decideClose(input("RIOT", 0.01, 14.0, 14.21, base = 10.0)).action)
        assertEquals("SKIP", Engine.decideClose(input("PLUG", 0.0, 0.0, 2.31)).action)
        assertEquals("SKIP", Engine.decideClose(input("PLUG", 0.0, 2.25, 2.31).copy(minutesToClose = 5.0)).action)
        assertEquals("SKIP", Engine.decideClose(input("PLUG", 0.0, 2.25, 2.31).copy(openOrders = 1)).action)
        assertEquals("ALERT", Engine.decideClose(input("PLUG", 0.0, 2.25, 2.31).copy(positionQty = 3.0)).action)
        assertEquals("SKIP", Engine.decideClose(input("PLUG", 0.0, 2.25, 2.31).copy(accountBlocked = true)).action)
        // cash limits the budget
        assertEquals(20, Engine.decideClose(input("PLUG", 0.0, 2.25, 2.31).copy(cash = 50.0)).qty)
    }

    @Test
    fun allocation() {
        var st = Engine.applySettings(State(), live(70, Sizing.COMPOUND, 1000.0), "t")
        assertEquals(700.0, st.capital("PLUG"), 1e-9)
        assertEquals(300.0, st.capital("RIOT"), 1e-9)
        assertEquals(listOf("PLUG", "RIOT"), st.settings.enabledSymbols())
        // profit on PLUG, then move the split: the combined capital is redistributed
        st = st.copy(ledger = st.ledger + ("PLUG" to 800.0))
        st = Engine.applySettings(st, st.settings.copy(plugSharePct = 50), "t")
        assertEquals(550.0, st.capital("PLUG"), 1e-9)
        assertEquals(550.0, st.capital("RIOT"), 1e-9)
        // RIOT only
        st = Engine.applySettings(st, st.settings.copy(plugSharePct = 0), "t")
        assertEquals(listOf("RIOT"), st.settings.enabledSymbols())
        assertEquals(1100.0, st.capital("RIOT"), 1e-9)
        assertEquals(0.0, Engine.budgetBase(st, "PLUG"), 0.0)
        // new amount starts fresh
        st = Engine.applySettings(st, st.settings.copy(totalUsd = 400.0, plugSharePct = 25), "t")
        assertEquals(100.0, st.capital("PLUG"), 1e-9)
        // fixed sizing uses the amount, not the ledger
        val fixed = Engine.applySettings(State(), live(25, Sizing.FIXED, 200.0), "t")
        assertEquals(50.0, Engine.budgetBase(fixed, "PLUG"), 1e-9)
        assertEquals(150.0, Engine.budgetBase(fixed, "RIOT"), 1e-9)
    }

    // ---------- full cycles ----------

    @Test
    fun liveCycleBothBotsCompounding() {
        setup(live(50, Sizing.COMPOUND, 200.0))
        broker.cash = 500.0
        broker.lastEquity = 500.0
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10   // +5%
        broker.open["RIOT"] = 14.00; broker.last["RIOT"] = 14.30 // +2.1%

        // Monday 15:39: too early (21 min is inside 10..30, so use 15:20 to test the window)
        broker.time("2026-11-16T15:20")
        runner.tick()
        assertTrue(broker.orders.isEmpty())
        assertTrue(store.rows.isEmpty())

        broker.time("2026-11-16T15:40")
        runner.tick()
        // 100 * 0.95 = 95 -> 45 PLUG @ 2.10, 6 RIOT @ 14.30
        assertEquals(listOf("PLUG:buy:45:cls", "RIOT:buy:6:cls"), broker.orders.map { "${it.symbol}:${it.side}:${it.qty}:${it.tif}" })
        assertEquals(listOf("PLUG:BUY", "RIOT:BUY"), actions("CLOSE"))

        // second tick in the window: nothing new
        broker.time("2026-11-16T15:47")
        runner.tick()
        assertEquals(2, broker.orders.size)

        broker.auction("cls", mapOf("PLUG" to 2.12, "RIOT" to 14.40))
        broker.time("2026-11-16T16:30")
        runner.tick()
        assertEquals(listOf("PLUG:BOUGHT", "RIOT:BOUGHT"), actions("FILL"))
        assertEquals(1, actions("REPORT").size)
        assertEquals(2, store.state.trades.count { it.status == TradeStatus.HOLDING })
        assertEquals(2, broker.orders.size) // too early for the opening order

        // 18:00: Alpaca would reject opg, the bot must not try
        broker.time("2026-11-16T18:00")
        runner.tick()
        assertEquals(2, broker.orders.size)

        broker.time("2026-11-16T19:10")
        runner.tick()
        assertEquals(listOf("PLUG:sell:45:opg", "RIOT:sell:6:opg"), broker.orders.drop(2).map { "${it.symbol}:${it.side}:${it.qty}:${it.tif}" })
        assertTrue(broker.orders[2].coid.length <= 48)
        // morning fallback run: orders exist, nothing new
        broker.time("2026-11-17T08:45")
        runner.tick()
        assertEquals(4, broker.orders.size)

        broker.auction("opg", mapOf("PLUG" to 2.22, "RIOT" to 14.10))
        broker.time("2026-11-17T09:32")
        runner.tick()
        // PLUG: 45 * (2.22 - 2.12) = +4.50 ; RIOT: 6 * (14.10 - 14.40) = -1.80
        assertEquals(104.5, store.state.capital("PLUG"), 1e-6)
        assertEquals(98.2, store.state.capital("RIOT"), 1e-6)
        assertEquals(4.5, store.state.pnl("PLUG"), 1e-6)
        assertEquals(2, store.state.trades.count { it.status == TradeStatus.CLOSED })
        assertTrue(notes.texts().contains("+4.50 USD"))
        assertTrue(notes.texts().contains("-1.80 USD"))

        // Tuesday close: PLUG red, RIOT only +0.5% -> no trades
        broker.open["PLUG"] = 2.30; broker.last["PLUG"] = 2.20
        broker.open["RIOT"] = 14.00; broker.last["RIOT"] = 14.07
        broker.time("2026-11-17T15:40")
        runner.tick()
        assertEquals(4, broker.orders.size)
        assertEquals(listOf("PLUG:NONE", "RIOT:NONE"), actions("CLOSE").takeLast(2))

        // Wednesday: PLUG green again, budget comes from the compounded capital 104.50 * 0.95 = 99.27 -> 47 @ 2.10
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.time("2026-11-18T15:40")
        runner.tick()
        assertEquals("PLUG:buy:47:cls", broker.orders.last().let { "${it.symbol}:${it.side}:${it.qty}:${it.tif}" })
    }

    @Test
    fun riotOnlyFixed() {
        setup(live(0, Sizing.FIXED, 100.0))
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.open["RIOT"] = 14.00; broker.last["RIOT"] = 14.30
        broker.time("2026-11-16T15:40")
        runner.tick()
        assertEquals(listOf("RIOT:buy:6"), broker.orders.map { "${it.symbol}:${it.side}:${it.qty}" })
        assertEquals(listOf("RIOT:BUY"), actions("CLOSE"))
    }

    @Test
    fun fridayBuySellsMondayAndSkipsHoliday() {
        setup(live(100, Sizing.FIXED, 100.0))
        broker.holidays = setOf("2026-11-26")
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.time("2026-11-20T15:40") // Friday
        runner.tick()
        broker.auction("cls", mapOf("PLUG" to 2.10))
        broker.time("2026-11-20T19:10")
        runner.tick()
        assertEquals("opg", broker.orders.last().tif)
        assertTrue(broker.orders.last().coid.contains("2026-11-23"))
        // weekend ticks do nothing more
        broker.time("2026-11-21T12:00")
        runner.tick()
        assertEquals(2, broker.orders.size)
        // holiday: market closed all day, no close run, no report, no "missed" alert
        broker.auction("opg", mapOf("PLUG" to 2.15))
        broker.time("2026-11-26T15:40")
        val before = store.rows.size
        runner.tick()
        broker.time("2026-11-26T16:30")
        runner.tick()
        assertEquals(1, store.rows.size - before) // only the SOLD fill row
        assertFalse(notes.texts().contains("No close run"))
    }

    @Test
    fun paperCycleUsesMarketOrders() {
        setup(Settings(active = true, mode = Mode.PAPER, totalUsd = 100.0, plugSharePct = 100))
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.time("2026-11-16T15:40")
        runner.tick()
        assertTrue(broker.orders.isEmpty()) // paper window is 2..8 minutes before the close
        broker.time("2026-11-16T15:55")
        runner.tick()
        assertEquals("PLUG:buy:45:day:filled", broker.orders.single().let { "${it.symbol}:${it.side}:${it.qty}:${it.tif}:${it.status}" })
        broker.time("2026-11-16T15:57")
        runner.tick() // fill is read, but the position bought today is not sold
        assertEquals(1, broker.orders.size)
        broker.time("2026-11-16T19:10")
        runner.tick()
        assertEquals(1, broker.orders.size)
        broker.last["PLUG"] = 2.20
        broker.time("2026-11-17T09:31")
        runner.tick()
        assertEquals("sell:day:filled", broker.orders.last().let { "${it.side}:${it.tif}:${it.status}" })
        broker.time("2026-11-17T09:36")
        runner.tick()
        assertEquals(4.5, store.state.pnl("PLUG"), 1e-6)
        assertEquals(2, broker.orders.size)
    }

    @Test
    fun signalsOnlySendsNothing() {
        setup(live(50).copy(ordersEnabled = false))
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.open["RIOT"] = 14.00; broker.last["RIOT"] = 14.30
        broker.time("2026-11-16T15:40")
        runner.tick()
        assertTrue(broker.orders.isEmpty())
        assertEquals(listOf("PLUG:BUY", "RIOT:BUY"), actions("CLOSE"))
        assertEquals("not sent", store.rows[0].orderStatus)
    }

    @Test
    fun sharedCashIsNotSpentTwice() {
        setup(live(50, Sizing.FIXED, 400.0))
        broker.cash = 250.0
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.open["RIOT"] = 14.00; broker.last["RIOT"] = 14.30
        broker.time("2026-11-16T15:40")
        runner.tick()
        // PLUG: min(200, 250) * .95 = 190 -> 90 shares = 189 ; RIOT: min(200, 61) * .95 = 57.95 -> 4 shares
        assertEquals(listOf(90, 4), broker.orders.map { it.qty })
        assertTrue(broker.orders.sumOf { it.qty * broker.last[it.symbol]!! } <= 250.0)
    }

    @Test
    fun offlineThenMissedAlert() {
        setup(live(100))
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.offline = true
        broker.time("2026-11-16T15:40")
        try {
            runner.tick()
            assertTrue(false)
        } catch (e: IOException) {
        }
        assertTrue(store.rows.isEmpty())
        broker.offline = false
        broker.time("2026-11-16T16:30")
        runner.tick()
        assertEquals(listOf("PLUG:MISSED"), actions("CLOSE"))
        assertTrue(notes.all.any { it.third && it.second.contains("No close run for PLUG") })
        // once per day only
        broker.time("2026-11-16T17:00")
        runner.tick()
        assertEquals(1, actions("CLOSE").size)
    }

    @Test
    fun auctionSellNotFilledIsRetriedNextOpen() {
        setup(live(100))
        broker.open["PLUG"] = 2.00; broker.last["PLUG"] = 2.10
        broker.time("2026-11-16T15:40")
        runner.tick()
        broker.auction("cls", mapOf("PLUG" to 2.10))
        broker.time("2026-11-16T19:10")
        runner.tick()
        broker.auctionsFill = false
        broker.auction("opg", mapOf("PLUG" to 2.15))
        broker.auctionsFill = true
        broker.time("2026-11-17T09:32")
        runner.tick()
        assertTrue(notes.all.any { it.third && it.second.contains("Sell order did not fill") })
        assertEquals(TradeStatus.HOLDING, store.state.trades.single().status)
        // close run: still holding -> ALERT, no second buy
        broker.time("2026-11-17T15:40")
        runner.tick()
        assertEquals("PLUG:ALERT", actions("CLOSE").last())
        assertEquals(2, broker.orders.size)
        // evening: new opening order for Wednesday
        broker.time("2026-11-17T19:10")
        runner.tick()
        assertEquals(3, broker.orders.size)
        assertTrue(broker.orders.last().coid.contains("2026-11-18"))
        broker.auction("opg", mapOf("PLUG" to 2.05))
        broker.time("2026-11-18T09:32")
        runner.tick()
        assertEquals(-4.5, store.state.pnl("PLUG"), 1e-6) // 90 * (2.05 - 2.10)
    }

    @Test
    fun untrackedPositionOfActiveBotIsSoldLikeN8n() {
        setup(live(100))
        broker.pos["PLUG"] = 10.0 to 2.00
        broker.pos["RIOT"] = 5.0 to 14.00 // RIOT bot is off: must be left alone
        broker.last["PLUG"] = 2.10; broker.last["RIOT"] = 14.0
        broker.time("2026-11-16T19:10")
        runner.tick()
        assertEquals(listOf("PLUG:sell:10"), broker.orders.map { "${it.symbol}:${it.side}:${it.qty}" })
    }

    @Test
    fun offAndNoKeys() {
        store.state = State()
        assertEquals("Bot is switched off", runner.tick())
        setup(live(100))
        val r = Runner(store, { null }, notes)
        assertTrue(r.tick().startsWith("No API keys"))
    }

    // ---------- schedule ----------

    @Test
    fun schedule() {
        val cal = listOf(CalDay("2026-11-25", "09:30", "16:00"), CalDay("2026-11-26", "", ""), CalDay("2026-11-27", "09:30", "13:00"), CalDay("2026-11-30", "09:30", "16:00"))
        fun next(s: String, mode: Mode) = Schedule.nextWake(FakeBroker.at(s), mode, cal).toLocalDateTime().toString()
        assertEquals("2026-11-25T15:40", next("2026-11-25T10:00", Mode.LIVE))
        assertEquals("2026-11-25T15:47", next("2026-11-25T15:40", Mode.LIVE))
        assertEquals("2026-11-25T16:30", next("2026-11-25T15:50", Mode.LIVE))
        assertEquals("2026-11-25T19:10", next("2026-11-25T16:31", Mode.LIVE))
        // Thanksgiving is skipped, Friday closes at 13:00
        assertEquals("2026-11-27T08:45", next("2026-11-25T19:11", Mode.LIVE))
        assertEquals("2026-11-27T09:32", next("2026-11-27T08:46", Mode.LIVE))
        assertEquals("2026-11-27T12:40", next("2026-11-27T09:33", Mode.LIVE))
        assertEquals("2026-11-27T12:55", next("2026-11-27T09:40", Mode.PAPER))
        assertEquals("2026-11-30T09:31", next("2026-11-27T20:00", Mode.PAPER))
        // without a calendar: plain weekdays
        assertEquals("2026-11-23T08:45", Schedule.nextWake(FakeBroker.at("2026-11-21T10:00"), Mode.LIVE, emptyList()).toLocalDateTime().toString())

        assertTrue(Schedule.opgAllowed(FakeBroker.at("2026-11-25T19:10"), cal))
        assertTrue(Schedule.opgAllowed(FakeBroker.at("2026-11-25T08:45"), cal))
        assertFalse(Schedule.opgAllowed(FakeBroker.at("2026-11-25T09:26"), cal))
        assertFalse(Schedule.opgAllowed(FakeBroker.at("2026-11-25T18:59"), cal))
        assertTrue(Schedule.opgAllowed(FakeBroker.at("2026-11-26T12:00"), cal))
    }

    @Test
    fun mightBeDue() {
        var st = Engine.applySettings(State(), live(50), "t")
        fun due(s: String) = Schedule.mightBeDue(st, FakeBroker.at(s))
        assertFalse(due("2026-11-17T11:00"))
        assertTrue(due("2026-11-17T15:40"))
        assertFalse(due("2026-11-17T15:55"))
        assertTrue(due("2026-11-17T16:30")) // report
        st = st.mark("report", "2026-11-17").mark("close:PLUG", "2026-11-17").mark("close:RIOT", "2026-11-17")
        assertFalse(due("2026-11-17T15:40"))
        assertFalse(due("2026-11-17T16:30"))
        st = st.copy(trades = listOf(Trade("t", "PLUG", "live", "2026-11-17", 5.0, status = TradeStatus.HOLDING)))
        assertFalse(due("2026-11-17T18:00"))
        assertTrue(due("2026-11-17T19:10"))
        st = st.copy(trades = listOf(Trade("t", "PLUG", "live", "2026-11-17", 5.0, status = TradeStatus.SELL_SENT)))
        assertFalse(due("2026-11-17T23:00"))
        assertTrue(due("2026-11-18T09:32"))
        assertFalse(Schedule.mightBeDue(st.copy(settings = st.settings.copy(active = false)), FakeBroker.at("2026-11-18T09:32")))
    }
}
