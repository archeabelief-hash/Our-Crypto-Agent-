package com.cryptoai.overlay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AllPairsScanner {
    data class HorizonPlan(
        val minutes: Int,
        val target: Double,
        val stop: Double,
        val score: Int,
        val net: Double,
        val rr: Double,
        val entry: Double,
        val slip: Double,
        val valid: Boolean
    )

    data class Pick(
        val product: String,
        val price: Double,
        val rsi: Double,
        val atr: Double,
        val history30d: Double,
        val plans: Map<Int, HorizonPlan>
    )

    data class Result(
        val scannedPairs: Int,
        val detailedPairs: Int,
        val amount: Double,
        val best1: Pick?,
        val best5: Pick?,
        val best15: Pick?,
        val suggestedProduct: String?,
        val suggestedWindow: Int?,
        val top: List<Pick>,
        val note: String
    )

    private data class Product(val id: String)
    private data class Ticker(
        val id: String,
        val price: Double,
        val bid: Double,
        val ask: Double,
        val volume: Double,
        val change: Double
    )

    private data class Level(val price: Double, val qty: Double)
    private data class Book(
        val asks: List<Level>,
        val mid: Double,
        val ask: Double,
        val spread: Double,
        val imbalance: Double,
        val depth: Double,
        val support: Double,
        val resistance: Double
    )

    private data class Candle(
        val rsi: Double,
        val atr: Double,
        val trend1: Double,
        val trend5: Double,
        val trend15: Double
    )

    private data class History(
        val ret24: Double,
        val ret7d: Double,
        val ret30d: Double,
        val abs90: Double,
        val expansion: Double
    )

    private data class Plan(
        val valid: Boolean,
        val net: Double,
        val rr: Double,
        val entry: Double,
        val slip: Double
    )

    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val fee = 0.006
    @Volatile private var stopped = false
    private var ws: WebSocket? = null

    fun stop() {
        stopped = true
        try { ws?.close(1000, "stop") } catch (_: Exception) {}
    }

    fun scan(amount: Double, progress: (String) -> Unit = {}, done: (Result) -> Unit) {
        Thread {
            val bank = amount.coerceAtLeast(1.0)
            try {
                stopped = false
                progress("Loading every live Coinbase USD pair…")
                val products = loadProducts()
                val ticks = collectTickers(products)
                if (stopped) return@Thread

                val finalists = preCandidates(products, ticks).take(14)
                val picks = mutableListOf<Pick>()
                finalists.forEachIndexed { index, ticker ->
                    if (stopped) return@Thread
                    progress("Checking ${index + 1}/${finalists.size}: ${ticker.id}")
                    try {
                        analyze(ticker, bank)?.let { picks += it }
                    } catch (_: Exception) {}
                    Thread.sleep(150)
                }

                fun bestFor(minutes: Int): Pick? {
                    return picks.maxWithOrNull(
                        compareBy<Pick> { it.plans[minutes]?.score ?: 0 }
                            .thenBy { it.plans[minutes]?.net ?: -999.0 }
                    )
                }

                val best1 = bestFor(1)
                val best5 = bestFor(5)
                val best15 = bestFor(15)
                val viable = listOf(1 to best1, 5 to best5, 15 to best15).mapNotNull { (minutes, pick) ->
                    val hp = pick?.plans?.get(minutes) ?: return@mapNotNull null
                    if (hp.valid && hp.net > 0.0 && hp.score >= 55 && hp.rr >= 0.85) {
                        Triple(minutes, pick, hp)
                    } else null
                }
                val winner = viable.maxWithOrNull(
                    compareBy<Triple<Int, Pick, HorizonPlan>> { it.third.score }
                        .thenBy { it.third.net }
                )

                val note = if (winner == null) {
                    "No clean positive-profit setup for $${money(bank)} right now after fees, visible liquidity and slippage."
                } else {
                    "${winner.second.product} is the strongest current ${winner.first}m setup for $${money(bank)}."
                }

                val top = picks.sortedByDescending { pick ->
                    pick.plans.values.maxOfOrNull { it.score } ?: 0
                }.take(6)

                done(
                    Result(
                        products.size,
                        picks.size,
                        bank,
                        best1,
                        best5,
                        best15,
                        winner?.second?.product,
                        winner?.first,
                        top,
                        note
                    )
                )
            } catch (e: Exception) {
                done(Result(0, 0, bank, null, null, null, null, null, emptyList(), "Scan error: ${e.message ?: "unknown"}"))
            }
        }.start()
    }

    private fun money(value: Double): String =
        if (value % 1.0 == 0.0) String.format("%.0f", value) else String.format("%.2f", value)

    private fun getJson(url: String): Any {
        val request = Request.Builder().url(url).header("User-Agent", "TwistedPsycheCrypto/0.8").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty().trim()
            return if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    }

    private fun loadProducts(): List<Product> {
        val data = getJson("https://api.exchange.coinbase.com/products") as JSONArray
        val result = mutableListOf<Product>()
        for (i in 0 until data.length()) {
            val p = data.getJSONObject(i)
            if (
                p.optString("quote_currency") == "USD" &&
                p.optString("status") == "online" &&
                !p.optBoolean("trading_disabled") &&
                !p.optBoolean("cancel_only") &&
                !p.optBoolean("post_only")
            ) {
                result += Product(p.optString("id"))
            }
        }
        return result
    }

    private fun collectTickers(products: List<Product>): Map<String, Ticker> {
        val result = java.util.concurrent.ConcurrentHashMap<String, Ticker>()
        val opened = CountDownLatch(1)
        ws = http.newWebSocket(
            Request.Builder().url("wss://advanced-trade-ws.coinbase.com").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                    products.map { it.id }.chunked(75).forEach { ids ->
                        webSocket.send(
                            JSONObject()
                                .put("type", "subscribe")
                                .put("channel", "ticker")
                                .put("product_ids", JSONArray(ids))
                                .toString()
                        )
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val events = JSONObject(text).optJSONArray("events") ?: return
                        for (i in 0 until events.length()) {
                            val tickers = events.getJSONObject(i).optJSONArray("tickers") ?: continue
                            for (k in 0 until tickers.length()) {
                                val t = tickers.getJSONObject(k)
                                val id = t.optString("product_id")
                                val price = t.optString("price").toDoubleOrNull() ?: 0.0
                                val bid = t.optString("best_bid").toDoubleOrNull() ?: 0.0
                                val ask = t.optString("best_ask").toDoubleOrNull() ?: 0.0
                                val volume = t.optString("volume_24_h").toDoubleOrNull() ?: 0.0
                                val change = (t.optString("price_percent_chg_24_h").toDoubleOrNull() ?: 0.0) / 100.0
                                if (id.isNotBlank() && price > 0.0) {
                                    result[id] = Ticker(id, price, bid, ask, volume, change)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        )

        opened.await(3, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 6500L
        while (System.currentTimeMillis() < deadline && !stopped) {
            Thread.sleep(300)
            if (result.size >= (products.size * 0.70).toInt().coerceAtLeast(40)) break
        }
        try { ws?.close(1000, "scan complete") } catch (_: Exception) {}
        return result
    }

    private fun preCandidates(products: List<Product>, tickers: Map<String, Ticker>): List<Ticker> {
        return products.mapNotNull { tickers[it.id] }
            .filter { t ->
                val mid = if (t.bid > 0.0 && t.ask >= t.bid) (t.bid + t.ask) / 2.0 else t.price
                val spread = if (t.bid > 0.0 && t.ask >= t.bid) (t.ask - t.bid) / mid else 0.02
                t.price * max(0.0, t.volume) >= 25_000.0 && spread <= 0.012
            }
            .sortedByDescending { t ->
                val mid = if (t.bid > 0.0 && t.ask >= t.bid) (t.bid + t.ask) / 2.0 else t.price
                val spread = if (t.bid > 0.0 && t.ask >= t.bid) (t.ask - t.bid) / mid else 0.02
                val quoteVolume = t.price * max(0.0, t.volume)
                0.55 * ((log10(quoteVolume + 1.0) - 4.4) / 3.4).coerceIn(0.0, 1.0) +
                    0.22 * (abs(t.change) / 0.12).coerceIn(0.0, 1.0) +
                    0.23 * (1.0 - spread / 0.012).coerceIn(0.0, 1.0)
            }
    }

    private fun analyze(ticker: Ticker, bank: Double): Pick? {
        val id = ticker.id
        val book = parseBook(getJson("https://api.exchange.coinbase.com/products/$id/book?level=2") as JSONObject) ?: return null
        val candle = parseCandles(getJson("https://api.exchange.coinbase.com/products/$id/candles?granularity=60") as JSONArray) ?: return null
        val history = loadHistory(id)

        val quality = (
            0.45 * (1.0 - book.spread / 0.008).coerceIn(0.0, 1.0) +
            0.35 * ((log10(book.depth + 1.0) - 3.5) / 3.0).coerceIn(0.0, 1.0) +
            0.20 * if (candle.rsi in 20.0..80.0) 1.0 else 0.7
        ).coerceIn(0.0, 1.0)

        val plans = linkedMapOf<Int, HorizonPlan>()
        for (minutes in listOf(1, 5, 15)) {
            val direction = direction(minutes, book, candle, ticker, history)
            val target = target(minutes, book, candle, direction, quality, history)
            val stopPct = if (minutes == 1) 0.0025 else 0.005
            val stop = book.support.coerceAtMost(book.mid * (1.0 - stopPct))
            val plan = plan(bank, book, target, stop)
            val strength = abs(direction) * 100.0 * quality
            val score = if (!plan.valid || direction <= 0.10) {
                0
            } else {
                val profitability = if (plan.net > 0.0) 10.0 else -25.0
                val slippagePenalty = (plan.slip / 0.01).coerceIn(0.0, 1.0) * 15.0
                (
                    strength * 0.55 +
                    quality * 20.0 +
                    (plan.rr / 2.0).coerceIn(0.0, 1.0) * 15.0 +
                    profitability -
                    slippagePenalty
                ).roundToInt().coerceIn(0, 100)
            }
            plans[minutes] = HorizonPlan(minutes, target, stop, score, plan.net, plan.rr, plan.entry, plan.slip, plan.valid)
        }

        return Pick(id, book.mid, candle.rsi, candle.atr, history?.ret30d ?: 0.0, plans)
    }

    private fun plan(bank: Double, book: Book, target: Double, stop: Double): Plan {
        val budget = bank / (1.0 + fee)
        var remaining = budget
        var cost = 0.0
        var qty = 0.0

        for (level in book.asks) {
            val capacity = level.price * level.qty
            val take = min(remaining, capacity)
            if (take <= 0.0) break
            qty += take / level.price
            cost += take
            remaining -= take
            if (remaining <= 0.0000001) break
        }

        if (remaining > max(0.01, budget * 0.001) || qty <= 0.0) {
            return Plan(false, -999.0, -99.0, 0.0, 1.0)
        }

        val entry = cost / qty
        val slip = max(0.0, (entry - book.ask) / book.ask)
        val exitDrag = max(book.spread / 2.0, slip * 0.7)
        val net = qty * target * (1.0 - exitDrag) * (1.0 - fee) - bank
        val stopNet = qty * stop * (1.0 - exitDrag) * (1.0 - fee)
        val loss = max(0.0, bank - stopNet)
        val rr = if (loss > 0.0) net / loss else 0.0
        return Plan(true, net, rr, entry, slip)
    }

    private fun direction(minutes: Int, book: Book, candle: Candle, ticker: Ticker, history: History?): Double {
        fun clamp(v: Double) = v.coerceIn(-1.0, 1.0)
        val rsiBias = clamp((candle.rsi - 50.0) / 35.0)
        val h24 = clamp((history?.ret24 ?: 0.0) / 0.08)
        val h7 = clamp((history?.ret7d ?: 0.0) / 0.18)
        val h30 = clamp((history?.ret30d ?: 0.0) / 0.35)
        val day = clamp(ticker.change / 0.10)

        return when (minutes) {
            1 -> clamp(0.48 * book.imbalance + 0.27 * clamp(candle.trend1 / 0.004) + 0.15 * rsiBias + 0.10 * h24)
            5 -> clamp(0.36 * book.imbalance + 0.24 * clamp(candle.trend5 / 0.012) + 0.12 * clamp(candle.trend15 / 0.025) + 0.10 * rsiBias + 0.10 * h24 + 0.08 * h7)
            else -> clamp(0.24 * book.imbalance + 0.18 * clamp(candle.trend5 / 0.012) + 0.20 * clamp(candle.trend15 / 0.025) + 0.08 * rsiBias + 0.12 * h24 + 0.12 * h7 + 0.03 * h30 + 0.03 * day)
        }
    }

    private fun target(minutes: Int, book: Book, candle: Candle, direction: Double, quality: Double, history: History?): Double {
        val trend = when (minutes) {
            1 -> candle.trend1
            5 -> candle.trend5
            else -> candle.trend15
        }
        val floor = when (minutes) {
            1 -> 0.0007
            5 -> 0.0015
            else -> 0.003
        }
        val cap = when (minutes) {
            1 -> 0.018
            5 -> 0.04
            else -> 0.075
        }
        val histMove = (history?.abs90 ?: 0.0) * sqrt(minutes / 60.0) * (history?.expansion ?: 1.0).coerceIn(0.65, 2.2)
        val base = max(floor, max(candle.atr * sqrt(minutes.toDouble()) * 0.68, max(abs(trend) * 0.58, histMove * 0.75)))
        val move = (base * (0.52 + max(0.0, direction) * 1.08) * (0.80 + quality * 0.42)).coerceIn(floor * 0.7, cap)
        val rawTarget = book.mid * (1.0 + move)
        return if (book.resistance > book.mid) min(rawTarget, book.resistance) else rawTarget
    }

    private fun parseBook(json: JSONObject): Book? {
        fun levels(name: String): List<Level> {
            val array = json.optJSONArray(name) ?: return emptyList()
            val result = mutableListOf<Level>()
            for (i in 0 until min(120, array.length())) {
                val row = array.optJSONArray(i) ?: continue
                val price = row.optString(0).toDoubleOrNull() ?: 0.0
                val qty = row.optString(1).toDoubleOrNull() ?: 0.0
                if (price > 0.0 && qty > 0.0) result += Level(price, qty)
            }
            return result
        }

        val bids = levels("bids")
        val asks = levels("asks")
        if (bids.isEmpty() || asks.isEmpty()) return null
        val bid = bids.first().price
        val ask = asks.first().price
        val mid = (bid + ask) / 2.0
        val bidDepth = bids.take(50).sumOf { it.price * it.qty }
        val askDepth = asks.take(50).sumOf { it.price * it.qty }
        val support = bids.filter { it.price >= mid * 0.985 }.maxByOrNull { it.price * it.qty }?.price ?: bid
        val resistance = asks.filter { it.price <= mid * 1.015 }.maxByOrNull { it.price * it.qty }?.price ?: ask
        return Book(
            asks,
            mid,
            ask,
            (ask - bid) / mid,
            (bidDepth - askDepth) / (bidDepth + askDepth).coerceAtLeast(0.000000001),
            bidDepth + askDepth,
            support,
            resistance
        )
    }

    private fun parseCandles(array: JSONArray): Candle? {
        data class Row(val time: Long, val low: Double, val high: Double, val close: Double)
        val rows = mutableListOf<Row>()
        for (i in 0 until array.length()) {
            val x = array.optJSONArray(i) ?: continue
            val close = x.optDouble(4)
            if (close > 0.0) rows += Row(x.optLong(0), x.optDouble(1), x.optDouble(2), close)
        }
        rows.sortBy { it.time }
        if (rows.size < 20) return null

        val closes = rows.map { it.close }
        val last = closes.last()
        fun trend(n: Int): Double {
            val base = closes[(closes.size - 1 - n).coerceAtLeast(0)]
            return if (base > 0.0) (last - base) / base else 0.0
        }

        var gains = 0.0
        var losses = 0.0
        for (i in closes.size - 14 until closes.size) {
            val delta = closes[i] - closes[i - 1]
            if (delta >= 0.0) gains += delta else losses -= delta
        }
        val rsi = if (losses == 0.0) 100.0 else 100.0 - 100.0 / (1.0 + (gains / 14.0) / (losses / 14.0))

        var trueRange = 0.0
        for (i in rows.size - 14 until rows.size) {
            val previous = rows[i - 1].close
            trueRange += max(rows[i].high - rows[i].low, max(abs(rows[i].high - previous), abs(rows[i].low - previous)))
        }
        val atr = (trueRange / 14.0) / last
        return Candle(rsi, atr, trend(1), trend(5), trend(15))
    }

    private fun loadHistory(product: String): History? {
        val now = System.currentTimeMillis() / 1000L
        val all = mutableListOf<JSONArray>()
        for (k in 3 downTo 1) {
            val start = now - k * 10L * 86400L
            val end = start + 10L * 86400L
            try {
                val url = "https://api.exchange.coinbase.com/products/$product/candles?granularity=3600&start=${Instant.ofEpochSecond(start)}&end=${Instant.ofEpochSecond(end)}"
                val array = getJson(url) as JSONArray
                for (i in 0 until array.length()) all += array.getJSONArray(i)
            } catch (_: Exception) {}
        }
        if (all.size < 72) return null

        val rows = all.distinctBy { it.optLong(0) }.sortedBy { it.optLong(0) }
        val closes = rows.map { it.optDouble(4) }
        fun ret(n: Int): Double {
            val base = closes[(closes.size - 1 - n).coerceAtLeast(0)]
            return if (base > 0.0) (closes.last() - base) / base else 0.0
        }

        val returns = (1 until closes.size).map { i ->
            if (closes[i - 1] > 0.0) (closes[i] - closes[i - 1]) / closes[i - 1] else 0.0
        }
        val absReturns = returns.map { abs(it) }.sorted()
        val p90 = absReturns[((absReturns.size - 1) * 0.90).toInt()]
        val recent = returns.takeLast(24).map { abs(it) }.average()
        val old = returns.dropLast(24).takeLast(336).map { abs(it) }
        val older = if (old.isEmpty()) recent else old.average()
        val expansion = if (older > 0.0) recent / older else 1.0
        return History(ret(24), ret(168), ret(min(719, closes.size - 1)), p90, expansion)
    }
}
