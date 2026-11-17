package com.pascalh.overnightbot.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime

data class HttpResponse(val code: Int, val body: String)

interface Http {
    @Throws(IOException::class)
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse
}

class UrlHttp : Http {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}

class AlpacaBroker(
    private val http: Http,
    private val keyId: String,
    private val secret: String,
    mode: Mode,
    private val retryWaitMs: Long = 3000,
) : Broker {
    private val base = if (mode == Mode.LIVE) "https://api.alpaca.markets" else "https://paper-api.alpaca.markets"
    private val data = "https://data.alpaca.markets"
    private val headers get() = mapOf("APCA-API-KEY-ID" to keyId, "APCA-API-SECRET-KEY" to secret)

    /** GET with up to 3 tries on network errors and 5xx/429 answers. */
    private fun get(url: String): Any? {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                val r = http.request("GET", url, headers, null)
                if (r.code in 200..299) return MiniJson.parse(r.body.ifBlank { "null" })
                if (r.code == 429 || r.code >= 500) {
                    last = IOException("Alpaca HTTP ${r.code}")
                } else {
                    throw BrokerException(r.code, errorText(r))
                }
            } catch (e: IOException) {
                last = e
            }
            if (attempt < 3 && retryWaitMs > 0) {
                try {
                    Thread.sleep(retryWaitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted")
                }
            }
        }
        throw last ?: IOException("Alpaca request failed")
    }

    private fun errorText(r: HttpResponse): String {
        val msg = try {
            MiniJson.parse(r.body).asObj().s("message")
        } catch (e: Exception) {
            ""
        }
        return ("HTTP ${r.code} " + msg.ifBlank { r.body }).trim().take(300)
    }

    private fun instant(s: String): Instant = OffsetDateTime.parse(s).toInstant()

    override fun clock(): Clock {
        val j = get("$base/v2/clock").asObj()
        return Clock(instant(j.s("timestamp")), j.b("is_open"), instant(j.s("next_open")), instant(j.s("next_close")))
    }

    override fun calendar(start: String, end: String): List<CalDay> =
        get("$base/v2/calendar?start=$start&end=$end").asArr().map { it.asObj() }
            .map { CalDay(it.s("date"), it.s("open"), it.s("close")) }

    override fun account(): Account {
        val j = get("$base/v2/account").asObj()
        return Account(
            cash = j.d("cash"), buyingPower = j.d("buying_power"), equity = j.d("equity"),
            lastEquity = j.d("last_equity"), blocked = j.b("trading_blocked") || j.b("account_blocked"),
        )
    }

    override fun positions(): List<Position> = get("$base/v2/positions").asArr().map { it.asObj() }.map {
        Position(it.s("symbol"), it.d("qty"), it.d("avg_entry_price"), it.d("current_price"), it.d("market_value"))
    }

    private fun toOrder(j: JObj) = Order(
        id = j.s("id"), symbol = j.s("symbol"), side = j.s("side"), status = j.s("status"),
        filledQty = j.d("filled_qty"), filledAvgPx = j.d("filled_avg_price"),
    )

    override fun openOrders(symbol: String): List<Order> =
        get("$base/v2/orders?status=open&symbols=$symbol").asArr().map { toOrder(it.asObj()) }
            .filter { it.symbol == symbol }

    override fun order(id: String): Order = toOrder(get("$base/v2/orders/$id").asObj())

    override fun closedOrdersSince(symbols: List<String>, afterIso: String): List<Order> {
        val after = URLEncoder.encode(afterIso, "UTF-8")
        val syms = symbols.joinToString("%2C")
        return get("$base/v2/orders?status=closed&direction=asc&limit=100&symbols=$syms&after=$after")
            .asArr().map { toOrder(it.asObj()) }
    }

    override fun snapshot(symbol: String, feed: String): Snapshot? {
        val all = get("$data/v2/stocks/snapshots?symbols=$symbol&feed=$feed").asObj()
        val s = all.o(symbol)
        if (s.isEmpty()) return null
        val daily = s.o("dailyBar")
        val bar = if (daily.isEmpty()) null else Bar(
            open = daily.d("o"), close = daily.d("c"),
            time = try {
                instant(daily.s("t"))
            } catch (e: Exception) {
                null
            },
        )
        return Snapshot(lastTrade = s.o("latestTrade").d("p"), dailyBar = bar, prevClose = s.o("prevDailyBar").d("c"))
    }

    /** No automatic retry: a repeated POST could place a second order. */
    override fun submit(symbol: String, qty: Int, side: String, tif: String, clientOrderId: String): Order {
        val body = MiniJson.stringify(
            mapOf(
                "symbol" to symbol, "qty" to qty.toString(), "side" to side, "type" to "market",
                "time_in_force" to tif, "client_order_id" to clientOrderId,
            )
        )
        val r = http.request("POST", "$base/v2/orders", headers, body)
        if (r.code !in 200..299) throw BrokerException(r.code, errorText(r))
        return toOrder(MiniJson.parse(r.body).asObj())
    }
}
