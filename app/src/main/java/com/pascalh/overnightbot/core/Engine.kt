package com.pascalh.overnightbot.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

fun usd(x: Double): String = String.format(Locale.US, "%.2f", x)
fun pct(x: Double): String = String.format(Locale.US, "%.2f", x * 100)
fun signedUsd(x: Double): String = (if (x >= 0) "+" else "-") + usd(Math.abs(x))

/** The trading rules, ported from the n8n "Trade at Close" workflow. */
object Engine {
    data class CloseInput(
        val symbol: String,
        val minGreen: Double,
        val accountBlocked: Boolean,
        val marketOpen: Boolean,
        val minutesToClose: Double,
        val windowMin: Double,
        val windowMax: Double,
        val openOrders: Int,
        val positionQty: Double,
        val price: Double,
        val todayOpen: Double,
        /** Money this bot may use before the cash buffer. */
        val budgetBase: Double,
        val buyingPower: Double,
        val cash: Double,
        val cashBuffer: Double,
    )

    data class Decision(val action: String, val reason: String, val qty: Int = 0, val intraday: Double = 0.0)

    /** Minutes before the close in which the buy order may be sent. */
    fun window(mode: Mode): Pair<Double, Double> = if (mode == Mode.LIVE) 10.0 to 30.0 else 2.0 to 8.0

    /** Money a bot may use for tonight's buy, before the cash buffer. */
    fun budgetBase(st: State, symbol: String): Double {
        val cfg = st.settings
        return if (cfg.sizing == Sizing.FIXED) cfg.totalUsd * cfg.share(symbol) else st.capital(symbol)
    }

    /** Today's opening price: the first daily bar (SIP, then IEX) that is dated today in New York. */
    fun todayOpen(nyDate: String, sip: Snapshot?, iex: Snapshot?): Double {
        for (bar in listOf(sip?.dailyBar, iex?.dailyBar)) {
            val t = bar?.time ?: continue
            if (t.atZone(NY).toLocalDate().toString() == nyDate) return bar.open
        }
        return 0.0
    }

    fun decideClose(i: CloseInput): Decision {
        val intraday = if (i.todayOpen > 0) i.price / i.todayOpen - 1 else 0.0
        val green = i.todayOpen > 0 && intraday > i.minGreen
        val need = if (i.minGreen > 0) " (needs more than +" + pct(i.minGreen) + "%)" else ""
        return when {
            i.accountBlocked -> Decision("SKIP", "Account is blocked for trading", 0, intraday)
            !i.marketOpen -> Decision("SKIP", "Market closed today (holiday or early close)", 0, intraday)
            i.minutesToClose < i.windowMin || i.minutesToClose > i.windowMax -> Decision(
                "SKIP",
                "Outside order window: " + String.format(Locale.US, "%.0f", i.minutesToClose) + " min to close (needs " +
                    String.format(Locale.US, "%.0f-%.0f", i.windowMin, i.windowMax) + ")",
                0, intraday,
            )
            i.openOrders > 0 -> Decision("SKIP", "Open order already exists for ${i.symbol} (duplicate protection)", 0, intraday)
            i.positionQty > 0 -> Decision(
                "ALERT",
                "Still holding ${MiniJson.stringify(i.positionQty)} shares at the close: the morning sell did not fill. Check Alpaca.",
                0, intraday,
            )
            i.todayOpen <= 0 -> Decision("SKIP", "No opening price for today, cannot check the green-day rule", 0, intraday)
            !green -> Decision(
                "NONE",
                (if (intraday > 0) "Not green enough" else "Red day") + ": price ${i.price} vs open ${i.todayOpen} (" +
                    pct(intraday) + "%)" + need + ", no overnight hold",
                0, intraday,
            )
            else -> {
                val budget = minOf(i.budgetBase, i.buyingPower, i.cash) * (1 - i.cashBuffer)
                val qty = if (i.price > 0) Math.floor(budget / i.price).toInt() else 0
                if (qty < 1) Decision("SKIP", "Not enough cash for 1 share (budget " + usd(maxOf(budget, 0.0)) + ")", 0, intraday)
                else Decision(
                    "BUY",
                    "Green day (" + pct(intraday) + "% above open ${i.todayOpen}): buy at close, sell at next open",
                    qty, intraday,
                )
            }
        }
    }

    /**
     * Applies new settings and keeps the per-bot capital consistent:
     * a new amount, sizing or mode starts the capital fresh; a new split moves the existing capital.
     */
    fun applySettings(st: State, new: Settings, nowIso: String): State {
        val old = st.settings
        var ledger = st.ledger
        var realized = st.realized
        var done = st.done
        val modeChanged = old.mode != new.mode
        if (modeChanged || old.totalUsd != new.totalUsd || old.sizing != new.sizing || ledger.isEmpty()) {
            ledger = SYMBOLS.associateWith { new.totalUsd * new.share(it) }
            if (modeChanged) {
                realized = emptyMap()
                done = emptyMap()
            }
        } else if (old.plugSharePct != new.plugSharePct) {
            val total = SYMBOLS.sumOf { ledger[it] ?: 0.0 }
            ledger = SYMBOLS.associateWith { total * new.share(it) }
        }
        if (new.active && (!old.active || modeChanged || done["since"].isNullOrBlank())) done = done + ("since" to nowIso)
        return st.copy(settings = new, ledger = ledger, realized = realized, done = done)
    }

    /** Sets the capital of both bots back to the starting amount and clears the profit counters. */
    fun resetCapital(st: State): State = st.copy(
        ledger = SYMBOLS.associateWith { st.settings.totalUsd * st.settings.share(it) },
        realized = emptyMap(),
    )
}

/** When the phone has to wake up, and whether a wake-up needs the network at all. */
object Schedule {
    data class Session(val open: LocalTime, val close: LocalTime)

    /** Trading hours of a day in New York time, or null when there is no session. */
    fun session(calendar: List<CalDay>, date: LocalDate): Session? {
        val day = calendar.firstOrNull { it.date == date.toString() }
        if (day != null) {
            if (day.open.isBlank() || day.close.isBlank()) return null // stored as "no session" (holiday, weekend)
            return try {
                Session(LocalTime.parse(day.open), LocalTime.parse(day.close))
            } catch (e: Exception) {
                Session(LocalTime.of(9, 30), LocalTime.of(16, 0))
            }
        }
        // No calendar for that day yet: assume a normal weekday session.
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return null
        return Session(LocalTime.of(9, 30), LocalTime.of(16, 0))
    }

    /** Alpaca rejects opening-auction orders between 09:28 and 19:00 New York time on trading days. */
    fun opgAllowed(now: ZonedDateTime, calendar: List<CalDay>): Boolean {
        val sess = session(calendar, now.toLocalDate()) ?: return true
        val t = now.toLocalTime()
        return t.isBefore(sess.open.minusMinutes(5)) || !t.isBefore(LocalTime.of(19, 5))
    }

    /** The next time the bot should run, as New York time. */
    fun nextWake(now: ZonedDateTime, mode: Mode, calendar: List<CalDay>): ZonedDateTime {
        val earliest = now.plusSeconds(30)
        for (offset in 0L..7L) {
            val date = now.toLocalDate().plusDays(offset)
            val sess = session(calendar, date) ?: continue
            val times = if (mode == Mode.LIVE) listOf(
                sess.open.minusMinutes(45),  // fallback: opening sell
                sess.open.plusMinutes(2),    // read the sell fill
                sess.close.minusMinutes(20), // buy decision
                sess.close.minusMinutes(13), // second try
                sess.close.plusMinutes(30),  // buy fill + daily report
                LocalTime.of(19, 10),        // queue the opening sell for the next session
            ) else listOf(
                sess.open.plusMinutes(1),    // sell with a market order
                sess.open.plusMinutes(6),
                sess.close.minusMinutes(5),  // buy with a market order
                sess.close.minusMinutes(3),
                sess.close.plusMinutes(30),
            )
            val next = times.map { ZonedDateTime.of(date, it, NY) }.filter { it.isAfter(earliest) }.minOrNull()
            if (next != null) return next
        }
        return now.plusHours(6)
    }

    /** Cheap check without network: is there anything the bot could do right now? */
    fun mightBeDue(st: State, now: ZonedDateTime): Boolean {
        val cfg = st.settings
        if (!cfg.active) return false
        val today = now.toLocalDate().toString()
        val t = now.toLocalTime()
        val modeName = cfg.mode.name.lowercase()
        val trades = st.trades.filter { it.mode == modeName }
        val sess = session(st.calendar, now.toLocalDate())
        if (sess != null) {
            val (lo, hi) = Engine.window(cfg.mode)
            val from = sess.close.minusMinutes(hi.toLong() + 2)
            val to = sess.close.minusMinutes(lo.toLong() - 1)
            if (!t.isBefore(from) && t.isBefore(to) && cfg.enabledSymbols().any { st.done["close:$it"] != today }) return true
            if (!t.isBefore(sess.close.plusMinutes(25)) && st.done["report"] != today) return true
            if (!t.isBefore(sess.close) && trades.any { it.status == TradeStatus.BUY_SENT }) return true
            // A sell sent in the evening belongs to the next open, so only look between the open and 19:00.
            if (!t.isBefore(sess.open.plusMinutes(1)) && t.isBefore(LocalTime.of(19, 0)) &&
                trades.any { it.status == TradeStatus.SELL_SENT }
            ) return true
            if (cfg.mode == Mode.PAPER && !t.isBefore(sess.open) && t.isBefore(sess.close) &&
                trades.any { it.status == TradeStatus.HOLDING && it.buyDate < today }
            ) return true
        }
        if (cfg.mode == Mode.LIVE && trades.any { it.status == TradeStatus.HOLDING } && opgAllowed(now, st.calendar)) return true
        return false
    }
}
