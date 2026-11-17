package com.pascalh.overnightbot.core

import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * One "tick" looks at the market clock and the saved state and does whatever is due:
 * read fills, sell at the open, decide the buy at the close, send the daily report.
 * It is safe to call at any time and as often as wanted.
 */
class Runner(
    private val store: Store,
    private val brokerFor: (Settings) -> Broker?,
    private val notifier: Notifier,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private class Ctx(val cfg: Settings, val broker: Broker, val clock: Clock) {
        val now: ZonedDateTime = clock.timestamp.atZone(NY)
        val today: String = now.toLocalDate().toString()
        val timeEt: String = now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        val modeName: String = cfg.mode.name.lowercase()
    }

    private fun save(st: State): State {
        store.save(st)
        return st
    }

    private fun log(
        c: Ctx, symbol: String, phase: String, action: String, reason: String,
        price: Double = 0.0, todayOpen: Double = 0.0, intraday: Double = 0.0, qty: Double = 0.0,
        status: String = "", error: String = "",
    ) {
        store.appendLog(
            LogRow(
                ts = nowMs(), date = c.today, timeEt = c.timeEt,
                mode = c.modeName + if (c.cfg.ordersEnabled) "" else " (signals only)",
                symbol = symbol, phase = phase, action = action, reason = reason, price = price,
                todayOpen = todayOpen, intradayPct = Math.round(intraday * 10000) / 100.0, qty = qty,
                orderStatus = status, error = error,
            )
        )
    }

    private fun upsert(st: State, t: Trade): State =
        if (st.trades.any { it.id == t.id }) st.copy(trades = st.trades.map { if (it.id == t.id) t else it })
        else st.copy(trades = st.trades + t)

    /** Throws IOException when Alpaca cannot be reached, so the caller can retry later. */
    @Synchronized
    @Throws(IOException::class)
    fun tick(): String {
        var st = store.load()
        val cfg = st.settings
        if (!cfg.active) return "Bot is switched off"
        val broker = brokerFor(cfg) ?: return "No API keys saved for ${cfg.mode.name.lowercase()} mode"
        val clock = broker.clock()
        val c = Ctx(cfg, broker, clock)
        try {
            val did = ArrayList<String>()
            // Store the next 8 days; days without a session are kept with blank times.
            val sessions = broker.calendar(c.today, c.now.toLocalDate().plusDays(7).toString())
            st = save(st.copy(calendar = (0L..7L).map { c.now.toLocalDate().plusDays(it).toString() }
                .map { d -> sessions.firstOrNull { it.date == d } ?: CalDay(d, "", "") }))
            val sess = Schedule.session(st.calendar, c.now.toLocalDate())
            st = syncFills(st, c, did)
            val positions = broker.positions()
            if (clock.isOpen) {
                val minutesToClose = Duration.between(clock.timestamp, clock.nextClose).seconds / 60.0
                if (cfg.mode == Mode.PAPER) st = sellRun(st, c, positions, "day", c.today, did)
                val (lo, hi) = Engine.window(cfg.mode)
                if (minutesToClose in lo..hi) st = closeRun(st, c, positions, minutesToClose, did)
                else if (cfg.mode == Mode.LIVE) st = missedOpenAlert(st, c, positions, did)
            } else {
                if (cfg.mode == Mode.LIVE) {
                    val minutesToOpen = Duration.between(clock.timestamp, clock.nextOpen).seconds / 60.0
                    if (minutesToOpen >= 3 && Schedule.opgAllowed(c.now, st.calendar)) {
                        val openDate = clock.nextOpen.atZone(NY).toLocalDate().toString()
                        st = sellRun(st, c, positions, "opg", openDate, did)
                    }
                }
                if (sess != null && !c.now.toLocalTime().isBefore(sess.close.plusMinutes(25)) && st.done["report"] != c.today) {
                    st = report(st, c, positions, sess, did)
                }
            }
            save(st)
            return if (did.isEmpty()) "Checked at ${c.timeEt} New York time: nothing to do right now" else did.joinToString("; ")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            val msg = (e.message ?: e.javaClass.simpleName).take(300)
            log(c, "", "ERROR", "ERROR", "Bot run failed", error = msg)
            notifier.notify("Overnight bot ERROR (${c.modeName})", msg, true)
            return "Error: $msg"
        }
    }

    // ---- fills ----

    private val terminal = setOf("canceled", "expired", "rejected", "done_for_day", "stopped", "suspended")

    private fun syncFills(start: State, c: Ctx, did: MutableList<String>): State {
        var st = start
        val pending = st.trades.filter {
            it.mode == c.modeName && (it.status == TradeStatus.BUY_SENT || it.status == TradeStatus.SELL_SENT)
        }
        for (t in pending) {
            val buying = t.status == TradeStatus.BUY_SENT
            val o = try {
                c.broker.order(if (buying) t.buyOrderId else t.sellOrderId)
            } catch (e: BrokerException) {
                Order("", t.symbol, if (buying) "buy" else "sell", "rejected", 0.0, 0.0)
            }
            val filled = o.status == "filled"
            if (!filled && o.status !in terminal) continue
            if (buying) {
                if (o.filledQty > 0) {
                    st = upsert(st, t.copy(status = TradeStatus.HOLDING, qty = o.filledQty, buyPx = o.filledAvgPx))
                    log(c, t.symbol, "FILL", "BOUGHT", "Bought ${MiniJson.stringify(o.filledQty)} @ ${o.filledAvgPx}",
                        price = o.filledAvgPx, qty = o.filledQty, status = o.status)
                    did.add("${t.symbol} buy filled")
                } else {
                    st = upsert(st, t.copy(status = TradeStatus.FAILED))
                    log(c, t.symbol, "FILL", "ALERT", "Buy order did not fill", status = o.status)
                    notifier.notify("${t.symbol} bot (${c.modeName})", "Buy order did not fill (${o.status}). No position tonight.", true)
                    did.add("${t.symbol} buy not filled")
                }
            } else {
                if (o.filledQty > 0) {
                    val pnl = (o.filledAvgPx - t.buyPx) * o.filledQty
                    val rest = t.qty - o.filledQty
                    st = st.copy(
                        ledger = st.ledger + (t.symbol to (st.capital(t.symbol) + pnl)),
                        realized = st.realized + (t.symbol to (st.pnl(t.symbol) + pnl)),
                    )
                    st = if (rest >= 1) upsert(st, t.copy(status = TradeStatus.HOLDING, qty = rest, sellOrderId = ""))
                    else upsert(st, t.copy(status = TradeStatus.CLOSED, sellPx = o.filledAvgPx, pnl = pnl))
                    val text = "Sold ${MiniJson.stringify(o.filledQty)} @ ${o.filledAvgPx}, bought @ ${t.buyPx}: " +
                        signedUsd(pnl) + " USD" + if (rest >= 1) " (${MiniJson.stringify(rest)} shares not sold)" else ""
                    log(c, t.symbol, "FILL", "SOLD", text, price = o.filledAvgPx, qty = o.filledQty, status = o.status)
                    notifier.notify("${t.symbol} bot (${c.modeName})", text, rest >= 1)
                    did.add("${t.symbol} sell filled")
                } else {
                    st = upsert(st, t.copy(status = TradeStatus.HOLDING, sellOrderId = "")).mark("missed:${t.symbol}", c.today)
                    log(c, t.symbol, "FILL", "ALERT", "Sell order did not fill. Still holding.", status = o.status)
                    notifier.notify("${t.symbol} bot (${c.modeName})", "Sell order did not fill (${o.status}). Still holding ${MiniJson.stringify(t.qty)} shares. Check Alpaca.", true)
                    did.add("${t.symbol} sell not filled")
                }
            }
            st = save(st)
        }
        return st
    }

    // ---- sell at the open ----

    private fun sellRun(start: State, c: Ctx, positions: List<Position>, tif: String, keyDate: String, did: MutableList<String>): State {
        var st = start
        for (sym in SYMBOLS) {
            val trades = st.trades.filter { it.symbol == sym && it.mode == c.modeName }
            if (trades.any { it.status == TradeStatus.BUY_SENT || it.status == TradeStatus.SELL_SENT }) continue
            val holding = trades.lastOrNull { it.status == TradeStatus.HOLDING }
            if (holding == null && !c.cfg.enabled(sym)) continue
            // Paper mode sells during the session: never sell what was bought today.
            if (tif == "day" && holding != null && holding.buyDate >= c.today) continue
            val pos = positions.firstOrNull { it.symbol == sym }
            val posQty = pos?.qty ?: 0.0
            if (pos == null || posQty <= 0.0) {
                if (holding != null) {
                    st = save(upsert(st, holding.copy(status = TradeStatus.CLOSED)))
                    log(c, sym, "OPEN", "NONE", "Tracked position is no longer in the account; trade closed without P&L")
                }
                continue
            }
            val key = "sell:$sym:$keyDate"
            val attempts = st.done[key]?.toIntOrNull() ?: 0
            if (attempts >= 3) continue
            val base = holding ?: Trade(
                id = "adopt-${sym.lowercase()}-${nowMs()}", symbol = sym, mode = c.modeName, buyDate = "",
                qty = posQty, buyPx = pos.avgEntry, status = TradeStatus.HOLDING,
            )
            val openSells = c.broker.openOrders(sym).filter { it.side == "sell" }
            if (openSells.isNotEmpty()) {
                st = save(upsert(st, base.copy(status = TradeStatus.SELL_SENT, sellOrderId = openSells[0].id)))
                continue
            }
            val qty = Math.floor(posQty).toInt()
            if (qty < 1) {
                if (st.done["frac:$sym"] != c.today) {
                    st = save(st.mark("frac:$sym", c.today))
                    log(c, sym, "OPEN", "ALERT", "Position below 1 share, cannot send the sell order", qty = posQty)
                    notifier.notify("$sym bot (${c.modeName})", "Position below 1 share, cannot send the sell order.", true)
                }
                continue
            }
            var trade = base
            var status = "not sent"
            var error = ""
            if (c.cfg.ordersEnabled) {
                try {
                    val o = c.broker.submit(sym, qty, "sell", tif, "ob-${sym.lowercase()}-$keyDate-sell${attempts + 1}-${c.modeName}")
                    status = o.status
                    trade = base.copy(status = TradeStatus.SELL_SENT, sellOrderId = o.id, qty = qty.toDouble())
                } catch (e: BrokerException) {
                    status = "rejected"
                    error = e.message ?: "rejected"
                }
            }
            st = save(upsert(st.mark(key, (attempts + 1).toString()), trade))
            val reason = if (tif == "opg") "Overnight: sell at the opening auction on $keyDate"
            else "Paper mode: sell with a market order after the open"
            val change = if (pos.avgEntry > 0) pos.current / pos.avgEntry - 1 else 0.0
            log(c, sym, "OPEN", "SELL", reason, price = pos.current, intraday = change, qty = qty.toDouble(), status = status, error = error)
            notifier.notify(
                "$sym bot (${c.modeName})",
                "Action: SELL\nHolding ${MiniJson.stringify(posQty)} shares, entry ${pos.avgEntry}, last ${pos.current} (${pct(change)}%)\n" +
                    reason + "\nOrder $qty shares: $status" + if (error.isNotEmpty()) "\nERROR: $error" else "",
                true,
            )
            did.add("$sym sell $status")
        }
        return st
    }

    private fun missedOpenAlert(start: State, c: Ctx, positions: List<Position>, did: MutableList<String>): State {
        var st = start
        for (t in st.trades.filter { it.mode == c.modeName && it.status == TradeStatus.HOLDING && it.buyDate < c.today }) {
            if (st.done["missed:${t.symbol}"] == c.today) continue
            if (positions.none { it.symbol == t.symbol && it.qty > 0 }) continue
            st = save(st.mark("missed:${t.symbol}", c.today))
            val text = "Still holding ${MiniJson.stringify(t.qty)} ${t.symbol} shares after the open: the opening sell was not placed or did not fill. " +
                "The bot will try again for the next open. Check Alpaca if you want to sell now."
            log(c, t.symbol, "OPEN", "ALERT", text, qty = t.qty)
            notifier.notify("${t.symbol} bot (${c.modeName})", text, true)
            did.add("${t.symbol} missed open")
        }
        return st
    }

    // ---- buy at the close ----

    private fun closeRun(start: State, c: Ctx, positions: List<Position>, minutesToClose: Double, did: MutableList<String>): State {
        var st = start
        val todo = c.cfg.enabledSymbols().filter { st.done["close:$it"] != c.today }
        if (todo.isEmpty()) return st
        val account = c.broker.account()
        var cash = account.cash
        var buyingPower = account.buyingPower
        val (lo, hi) = Engine.window(c.cfg.mode)
        val tif = if (c.cfg.mode == Mode.LIVE) "cls" else "day"
        for (sym in todo) {
            try {
                val iex = c.broker.snapshot(sym, "iex")
                val sip = try {
                    c.broker.snapshot(sym, "delayed_sip")
                } catch (e: Exception) {
                    null
                }
                val price = (iex?.lastTrade ?: 0.0).takeIf { it > 0 } ?: (iex?.dailyBar?.close ?: 0.0)
                if (price <= 0) throw IllegalStateException("Missing price data for $sym")
                val todayOpen = Engine.todayOpen(c.today, sip, iex)
                val d = Engine.decideClose(
                    Engine.CloseInput(
                        symbol = sym, minGreen = c.cfg.minGreen(sym), accountBlocked = account.blocked,
                        marketOpen = c.clock.isOpen, minutesToClose = minutesToClose, windowMin = lo, windowMax = hi,
                        openOrders = c.broker.openOrders(sym).size,
                        positionQty = positions.firstOrNull { it.symbol == sym }?.qty ?: 0.0,
                        price = price, todayOpen = todayOpen, budgetBase = Engine.budgetBase(st, sym),
                        buyingPower = buyingPower, cash = cash, cashBuffer = c.cfg.cashBufferPct,
                    )
                )
                var status = "not sent"
                var error = ""
                if (d.action == "BUY" && c.cfg.ordersEnabled) {
                    try {
                        val coid = "ob-${sym.lowercase()}-${c.today}-buy-${c.modeName}"
                        val o = c.broker.submit(sym, d.qty, "buy", tif, coid)
                        status = o.status
                        st = upsert(st, Trade(id = coid, symbol = sym, mode = c.modeName, buyDate = c.today,
                            qty = d.qty.toDouble(), buyOrderId = o.id, status = TradeStatus.BUY_SENT))
                        cash -= d.qty * price
                        buyingPower -= d.qty * price
                    } catch (e: BrokerException) {
                        status = "rejected"
                        error = e.message ?: "rejected"
                    }
                }
                st = save(st.mark("close:$sym", c.today))
                log(c, sym, "CLOSE", d.action, d.reason, price = price, todayOpen = todayOpen, intraday = d.intraday,
                    qty = d.qty.toDouble(), status = status, error = error)
                notifier.notify(
                    "$sym bot (${c.modeName}${if (c.cfg.ordersEnabled) "" else ", signals only"})",
                    "Action: ${d.action}\nPrice $price vs open $todayOpen (${pct(d.intraday)}%)\n${d.reason}" +
                        (if (d.action == "BUY") "\nOrder ${d.qty} shares: $status" else "") +
                        (if (error.isNotEmpty()) "\nERROR: $error" else ""),
                    d.action !in setOf("NONE", "SKIP") || error.isNotEmpty(),
                )
                did.add("$sym ${d.action}")
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).take(300)
                st = save(st.mark("close:$sym", c.today))
                log(c, sym, "CLOSE", "ERROR", "Close run failed", error = msg)
                notifier.notify("$sym bot ERROR (${c.modeName})", msg, true)
                did.add("$sym ERROR")
            }
        }
        return st
    }

    // ---- daily report ----

    private fun report(start: State, c: Ctx, positions: List<Position>, sess: Schedule.Session, did: MutableList<String>): State {
        var st = start
        val closeInstant = ZonedDateTime.of(c.now.toLocalDate(), sess.close, NY).toInstant()
        val since = try {
            Instant.parse(st.done["since"] ?: "")
        } catch (e: Exception) {
            null
        }
        if (since != null && since.isBefore(closeInstant.minusSeconds(1800))) {
            for (sym in c.cfg.enabledSymbols().filter { st.done["close:$it"] != c.today }) {
                val text = "No close run for $sym today: the phone was offline or asleep during the order window. No trade tonight."
                log(c, sym, "CLOSE", "MISSED", text)
                notifier.notify("$sym bot (${c.modeName})", text, true)
            }
        }
        val acc = c.broker.account()
        val dayStart = c.now.toLocalDate().atStartOfDay(NY).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val fills = c.broker.closedOrdersSince(SYMBOLS, dayStart).filter { it.filledQty > 0 }
            .map { it.side.uppercase() + " " + MiniJson.stringify(it.filledQty) + " " + it.symbol + " @ " + it.filledAvgPx }
        val pnl = acc.equity - acc.lastEquity
        val pnlPct = if (acc.lastEquity > 0) pnl / acc.lastEquity else 0.0
        val sb = StringBuilder()
        sb.append("Equity: $").append(usd(acc.equity)).append(" (day ").append(signedUsd(pnl)).append(" / ").append(pct(pnlPct)).append("%)\n")
        sb.append("Cash: $").append(usd(acc.cash)).append('\n')
        for (sym in SYMBOLS) {
            val pos = positions.firstOrNull { it.symbol == sym }
            if (!c.cfg.enabled(sym) && pos == null && st.pnl(sym) == 0.0) continue
            sb.append(sym).append(": ").append(MiniJson.stringify(pos?.qty ?: 0.0)).append(" shares ($").append(usd(pos?.marketValue ?: 0.0)).append(")")
            if (c.cfg.sizing == Sizing.COMPOUND) sb.append(", capital $").append(usd(st.capital(sym)))
            sb.append(", realized ").append(signedUsd(st.pnl(sym))).append('\n')
        }
        sb.append("Fills today: ").append(if (fills.isEmpty()) "none" else fills.joinToString("; "))
        st = save(st.mark("report", c.today))
        log(c, "", "REPORT", "REPORT", sb.toString().replace("\n", " | "))
        notifier.notify("Overnight bot daily report (${c.modeName}) ${c.today}", sb.toString(), false)
        did.add("daily report")
        return st
    }
}
