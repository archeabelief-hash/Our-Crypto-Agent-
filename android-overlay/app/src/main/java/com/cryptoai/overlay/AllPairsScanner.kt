package com.cryptoai.overlay

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

class AllPairsScanner {
    data class Pick(
        val product: String,
        val price: Double,
        val target: Double,
        val stop: Double,
        val score50: Int,
        val score100: Int,
        val net50: Double,
        val net100: Double,
        val rr50: Double,
        val rr100: Double
    )

    data class Result(
        val scannedPairs: Int,
        val detailedPairs: Int,
        val best50: Pick?,
        val best100: Pick?,
        val suggestedAmount: Int?,
        val suggestedProduct: String?,
        val top: List<Pick>,
        val note: String
    )

    private data class Product(val id: String)
    private data class Ticker(val id: String, val price: Double, val bid: Double, val ask: Double, val volume24h: Double, val change24h: Double)
    private data class Level(val price: Double, val qty: Double)
    private data class Book(val bids: List<Level>, val asks: List<Level>, val bid: Double, val ask: Double, val mid: Double, val spread: Double, val imbalance: Double, val topShare: Double, val depth: Double, val support: Double, val resistance: Double, val supportStrength: Double, val resistanceStrength: Double)
    private data class Candle(val rsi: Double, val atr: Double, val trend5: Double, val trend15: Double)
    private data class Plan(val valid: Boolean, val net: Double, val loss: Double, val rr: Double)

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val fee = 0.006
    @Volatile private var stopped = false
    private var ws: WebSocket? = null

    fun stop() { stopped = true; try { ws?.close(1000, "stop") } catch (_: Exception) {} }

    fun scan(progress: (String) -> Unit = {}, done: (Result) -> Unit) {
        Thread {
            try {
                stopped = false
                progress("Loading every live Coinbase USD pair…")
                val products = loadProducts()
                if (products.isEmpty()) throw IllegalStateException("No live USD pairs returned")
                progress("Listening to ${products.size} live pairs…")
                val tickers = collectTickers(products)
                if (stopped) return@Thread
                val finalists = preCandidates(products, tickers).take(18)
                progress("Deep checking ${finalists.size} strongest candidates…")
                val picks = mutableListOf<Pick>()
                finalists.forEachIndexed { i, t ->
                    if (stopped) return@Thread
                    progress("Checking ${i + 1}/${finalists.size}: ${t.id}")
                    try { analyze(t)?.let { picks += it } } catch (_: Exception) {}
                    Thread.sleep(180)
                }
                val best50 = picks.maxWithOrNull(compareBy<Pick> { it.score50 }.thenBy { it.net50 })
                val best100 = picks.maxWithOrNull(compareBy<Pick> { it.score100 }.thenBy { it.net100 })
                val good50 = best50 != null && best50.net50 > 0 && best50.rr50 >= 1.0 && best50.score50 >= 58
                val good100 = best100 != null && best100.net100 > 0 && best100.rr100 >= 1.0 && best100.score100 >= 58
                val suggestion = when {
                    good100 && best100!!.score100 >= 74 && best100.rr100 >= 1.25 -> 100
                    good50 -> 50
                    good100 -> 50
                    else -> null
                }
                val product = when (suggestion) { 100 -> best100?.product; 50 -> if (good50) best50?.product else best100?.product; else -> null }
                val note = if (suggestion == null) "No pair currently clears our conservative fee, liquidity and risk checks." else "$product currently has the strongest setup for a $$suggestion manual trade."
                val top = picks.sortedByDescending { max(it.score50, it.score100) }.take(6)
                done(Result(products.size, picks.size, best50, best100, suggestion, product, top, note))
            } catch (e: Exception) {
                done(Result(0, 0, null, null, null, null, emptyList(), "Scan error: ${e.message ?: "unknown"}"))
            }
        }.start()
    }

    private fun getJson(url: String): Any {
        client.newCall(Request.Builder().url(url).header("User-Agent", "TwistedPsycheCrypto/0.7").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val s = r.body?.string().orEmpty().trim()
            return if (s.startsWith("[")) JSONArray(s) else JSONObject(s)
        }
    }

    private fun loadProducts(): List<Product> {
        val a = getJson("https://api.exchange.coinbase.com/products") as JSONArray
        val out = mutableListOf<Product>()
        for (i in 0 until a.length()) {
            val p = a.getJSONObject(i)
            if (p.optString("quote_currency") == "USD" && p.optString("status") == "online" && !p.optBoolean("trading_disabled") && !p.optBoolean("cancel_only") && !p.optBoolean("post_only")) out += Product(p.optString("id"))
        }
        return out
    }

    private fun collectTickers(products: List<Product>): Map<String, Ticker> {
        val map = java.util.concurrent.ConcurrentHashMap<String, Ticker>()
        val opened = CountDownLatch(1)
        ws = client.newWebSocket(Request.Builder().url("wss://advanced-trade-ws.coinbase.com").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.countDown()
                products.map { it.id }.chunked(60).forEach { ids ->
                    webSocket.send(JSONObject().put("type", "subscribe").put("channel", "ticker").put("product_ids", JSONArray(ids)).toString())
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val j = JSONObject(text); val events = j.optJSONArray("events") ?: return
                    for (i in 0 until events.length()) {
                        val ts = events.getJSONObject(i).optJSONArray("tickers") ?: continue
                        for (k in 0 until ts.length()) {
                            val t = ts.getJSONObject(k); val id = t.optString("product_id")
                            val price = t.optString("price").toDoubleOrNull() ?: t.optDouble("price")
                            val bid = t.optString("best_bid").toDoubleOrNull() ?: t.optDouble("best_bid")
                            val ask = t.optString("best_ask").toDoubleOrNull() ?: t.optDouble("best_ask")
                            val vol = t.optString("volume_24_h").toDoubleOrNull() ?: t.optDouble("volume_24_h")
                            val chg = (t.optString("price_percent_chg_24_h").toDoubleOrNull() ?: t.optDouble("price_percent_chg_24_h")) / 100.0
                            if (id.isNotBlank() && price > 0) map[id] = Ticker(id, price, bid, ask, vol, chg)
                        }
                    }
                } catch (_: Exception) {}
            }
        })
        opened.await(3, TimeUnit.SECONDS)
        val deadline = System.currentTimeMillis() + 6500
        while (System.currentTimeMillis() < deadline && !stopped) {
            Thread.sleep(350)
            if (map.size >= (products.size * 0.70).toInt().coerceAtLeast(40)) break
        }
        try { ws?.close(1000, "scan complete") } catch (_: Exception) {}
        return map
    }

    private fun preCandidates(products: List<Product>, tickers: Map<String, Ticker>): List<Ticker> {
        fun clamp(v: Double) = v.coerceIn(0.0, 1.0)
        return products.mapNotNull { tickers[it.id] }.filter { t ->
            val mid = if (t.bid > 0 && t.ask >= t.bid) (t.bid + t.ask) / 2 else t.price
            val spread = if (t.bid > 0 && t.ask >= t.bid) (t.ask - t.bid) / mid else 0.02
            val quoteVol = t.price * max(0.0, t.volume24h)
            quoteVol >= 25_000 && spread <= 0.012
        }.sortedByDescending { t ->
            val mid = if (t.bid > 0 && t.ask >= t.bid) (t.bid + t.ask) / 2 else t.price
            val spread = if (t.bid > 0 && t.ask >= t.bid) (t.ask - t.bid) / mid else 0.02
            val quoteVol = t.price * max(0.0, t.volume24h)
            val liq = clamp((log10(quoteVol + 1) - 4.4) / 3.4)
            val motion = clamp(abs(t.change24h) / 0.12)
            val spreadQ = clamp(1 - spread / 0.012)
            0.55 * liq + 0.22 * motion + 0.23 * spreadQ + if (t.change24h > 0) 0.08 else 0.0
        }
    }

    private fun analyze(t: Ticker): Pick? {
        val bookJson = getJson("https://api.exchange.coinbase.com/products/${t.id}/book?level=2") as JSONObject
        val candlesJson = getJson("https://api.exchange.coinbase.com/products/${t.id}/candles?granularity=60") as JSONArray
        val b = book(bookJson) ?: return null
        val c = candles(candlesJson) ?: return null
        val rsiBias = ((c.rsi - 50) / 35).coerceIn(-1.0, 1.0)
        val dayBias = (t.change24h / 0.10).coerceIn(-1.0, 1.0)
        val direction = (0.40 * b.imbalance + 0.23 * (c.trend5 / 0.01).coerceIn(-1.0, 1.0) + 0.17 * (c.trend15 / 0.02).coerceIn(-1.0, 1.0) + 0.12 * rsiBias + 0.08 * dayBias).coerceIn(-1.0, 1.0)
        val hollow = when { b.topShare < .12 -> .55; b.topShare < .22 -> .75; b.topShare < .35 -> .90; else -> 1.0 }
        val spreadQ = (1 - b.spread / .008).coerceIn(0.0, 1.0)
        val depthQ = ((log10(b.depth + 1) - 3.8) / 3.0).coerceIn(0.0, 1.0)
        val quoteVol = t.price * max(0.0, t.volume24h)
        val overheat = when { c.rsi > 78 -> .75; c.rsi < 22 -> .82; else -> 1.0 }
        val quality = (0.34 * hollow + 0.26 * spreadQ + 0.22 * depthQ + 0.18 * (log10(quoteVol + 1) / 8).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0) * overheat
        val baseMove = max(.005, max(c.atr * sqrt(12.0) * .70, abs(c.trend15) * .65))
        val move = (baseMove * (.62 + abs(direction) * .95) * (.8 + quality * .4)).coerceIn(.003, .06)
        var target = b.mid * (1 + move)
        if (b.resistance > b.mid * 1.0015 && b.resistance < target && b.resistanceStrength >= 2.2) target = b.resistance
        var stop = b.mid * (1 - max(c.atr * 1.8, .005))
        if (b.support < b.mid && b.support > stop && b.supportStrength >= 2.2) stop = b.support * (1 - .0015)
        val p50 = plan(50.0, b, target, stop); val p100 = plan(100.0, b, target, stop)
        val strength = abs(direction) * 100 * quality
        fun rank(p: Plan): Int {
            if (!p.valid || direction <= .12) return 0
            val edge = (p.net / 2.0).coerceIn(-1.0, 1.0)
            val rr = (p.rr / 2.0).coerceIn(0.0, 1.0)
            return (strength * .56 + quality * 100 * .20 + max(0.0, edge) * 100 * .14 + rr * 100 * .10 - if (p.net <= 0) 28 else 0).roundToInt().coerceIn(0, 100)
        }
        return Pick(t.id, b.mid, target, stop, rank(p50), rank(p100), p50.net, p100.net, p50.rr, p100.rr)
    }

    private fun book(j: JSONObject): Book? {
        fun levels(name: String): List<Level> { val a = j.optJSONArray(name) ?: return emptyList(); return (0 until min(80, a.length())).mapNotNull { i -> val x = a.optJSONArray(i) ?: return@mapNotNull null; val p = x.optString(0).toDoubleOrNull() ?: 0.0; val q = x.optString(1).toDoubleOrNull() ?: 0.0; if (p > 0 && q > 0) Level(p, q) else null } }
        val bids = levels("bids"); val asks = levels("asks"); if (bids.isEmpty() || asks.isEmpty()) return null
        val bid = bids.first().price; val ask = asks.first().price; val mid = (bid + ask) / 2; val spread = (ask - bid) / mid
        val bn = bids.take(35).map { it.price * it.qty }; val an = asks.take(35).map { it.price * it.qty }; val bd = bn.sum(); val ad = an.sum(); val imb = (bd - ad) / (bd + ad).coerceAtLeast(1e-9)
        fun med(v: List<Double>): Double { val s = v.sorted(); return if (s.isEmpty()) 1.0 else s[s.size / 2].coerceAtLeast(1e-9) }
        val support = bids.filter { it.price >= mid * .985 }.maxByOrNull { it.price * it.qty } ?: bids.first(); val resistance = asks.filter { it.price <= mid * 1.015 }.maxByOrNull { it.price * it.qty } ?: asks.first()
        return Book(bids, asks, bid, ask, mid, spread, imb, (bn.take(5).sum() + an.take(5).sum()) / (bd + ad).coerceAtLeast(1e-9), bd + ad, support.price, resistance.price, support.price * support.qty / med(bn), resistance.price * resistance.qty / med(an))
    }

    private fun candles(a: JSONArray): Candle? {
        data class C(val low: Double, val high: Double, val close: Double)
        val rows = (0 until a.length()).mapNotNull { i -> val x = a.optJSONArray(i) ?: return@mapNotNull null; val lo = x.optDouble(1); val hi = x.optDouble(2); val cl = x.optDouble(4); if (cl > 0) Triple(x.optLong(0), C(lo, hi, cl), Unit) else null }.sortedBy { it.first }.map { it.second }
        if (rows.size < 20) return null
        val close = rows.map { it.close }; val last = close.last(); val trend5 = (last - close[close.size - 6]) / close[close.size - 6]; val trend15 = (last - close[close.size - 16]) / close[close.size - 16]
        var gains = 0.0; var losses = 0.0
        for (i in close.size - 14 until close.size) { val d = close[i] - close[i - 1]; if (d >= 0) gains += d else losses -= d }
        val rsi = if (losses == 0.0) 100.0 else 100 - 100 / (1 + (gains / 14) / (losses / 14))
        var tr = 0.0
        for (i in rows.size - 14 until rows.size) { val prev = rows[i - 1].close; tr += max(rows[i].high - rows[i].low, max(abs(rows[i].high - prev), abs(rows[i].low - prev))) }
        return Candle(rsi, (tr / 14) / last, trend5, trend15)
    }

    private fun plan(bank: Double, b: Book, target: Double, stop: Double): Plan {
        val budget = bank / (1 + fee); var rem = budget; var qty = 0.0; var cost = 0.0
        for (l in b.asks) { val use = min(rem, l.price * l.qty); if (use <= 0) break; qty += use / l.price; cost += use; rem -= use; if (rem <= .000001) break }
        if (qty <= 0 || rem > max(.01, budget * .001)) return Plan(false, -999.0, 999.0, -99.0)
        val entry = cost / qty; val slip = max(0.0, (entry - b.ask) / b.ask); val exitDrag = max(b.spread / 2, slip * .7)
        val targetNet = qty * target * (1 - exitDrag) * (1 - fee); val net = targetNet - bank
        val stopNet = qty * stop * (1 - exitDrag) * (1 - fee); val loss = max(0.0, bank - stopNet); val rr = if (loss > 1e-9) net / loss else 0.0
        return Plan(true, net, loss, rr)
    }
}
