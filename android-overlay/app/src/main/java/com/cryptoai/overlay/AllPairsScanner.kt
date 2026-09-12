package com.cryptoai.overlay

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.*

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
    private data class Ticker(val id: String, val price: Double, val bid: Double, val ask: Double, val volume24h: Double, val change24h: Double)
    private data class Level(val price: Double, val qty: Double)
    private data class Book(val bids: List<Level>, val asks: List<Level>, val bid: Double, val ask: Double, val mid: Double, val spread: Double, val imbalance: Double, val topShare: Double, val depth: Double, val support: Double, val resistance: Double, val supportStrength: Double, val resistanceStrength: Double)
    private data class Candle(val rsi: Double, val atr: Double, val trend1: Double, val trend5: Double, val trend15: Double)
    private data class History(val ret24: Double, val ret7d: Double, val ret30d: Double, val abs90: Double, val expansion: Double)
    private data class Fill(val qty: Double, val avgEntry: Double, val buyFee: Double)
    private data class Plan(val valid: Boolean, val net: Double, val loss: Double, val rr: Double, val entry: Double, val slip: Double)

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val fee = 0.006
    @Volatile private var stopped = false
    private var ws: WebSocket? = null

    fun stop() { stopped = true; try { ws?.close(1000, "stop") } catch (_: Exception) {} }

    fun scan(amount: Double, progress: (String) -> Unit = {}, done: (Result) -> Unit) {
        Thread {
            val bank = amount.coerceAtLeast(1.0)
            try {
                stopped = false
                progress("Loading every live Coinbase USD pair…")
                val products = loadProducts()
                if (products.isEmpty()) throw IllegalStateException("No live USD pairs returned")
                progress("Listening to ${products.size} live pairs…")
                val tickers = collectTickers(products)
                if (stopped) return@Thread
                val finalists = preCandidates(products, tickers).take(14)
                progress("Deep checking ${finalists.size} strongest candidates for $${bank.money()}…")
                val picks = mutableListOf<Pick>()
                finalists.forEachIndexed { i, t ->
                    if (stopped) return@Thread
                    progress("Checking ${i + 1}/${finalists.size}: ${t.id}")
                    try { analyze(t, bank)?.let { picks += it } } catch (_: Exception) {}
                    Thread.sleep(160)
                }
                fun bestFor(mins: Int): Pick? = picks.maxWithOrNull(compareBy<Pick> { it.plans[mins]?.score ?: 0 }.thenBy { it.plans[mins]?.net ?: -999.0 })
                val best1 = bestFor(1); val best5 = bestFor(5); val best15 = bestFor(15)
                val candidates = listOf(1 to best1, 5 to best5, 15 to best15).mapNotNull { (mins, p) ->
                    val hp = p?.plans?.get(mins) ?: return@mapNotNull null
                    if (hp.valid && hp.net > 0 && hp.score >= 55 && hp.rr >= .85) Triple(mins, p, hp) else null
                }
                val winner = candidates.maxWithOrNull(compareBy<Triple<Int, Pick, HorizonPlan>> { it.third.score }.thenBy { it.third.net })
                val note = if (winner == null) "No clean positive-profit setup for $${bank.money()} right now after fees, visible liquidity and slippage." else "${winner.second.product} is the strongest current ${winner.first}m setup for $${bank.money()}."
                val top = picks.sortedByDescending { p -> p.plans.values.maxOfOrNull { it.score } ?: 0 }.take(6)
                done(Result(products.size, picks.size, bank, best1, best5, best15, winner?.second?.product, winner?.first, top, note))
            } catch (e: Exception) {
                done(Result(0, 0, bank, null, null, null, null, null, emptyList(), "Scan error: ${e.message ?: "unknown"}"))
            }
        }.start()
    }

    private fun Double.money(): String = if (this % 1.0 == 0.0) String.format("%.0f", this) else String.format("%.2f", this)

    private fun getJson(url: String): Any {
        client.newCall(Request.Builder().url(url).header("User-Agent", "TwistedPsycheCrypto/0.8").build()).execute().use { r ->
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
                products.map { it.id }.chunked(75).forEach { ids ->
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
            Thread.sleep(300)
            if (map.size >= (products.size * .70).toInt().coerceAtLeast(40)) break
        }
        try { ws?.close(1000, "scan complete") } catch (_: Exception) {}
        return map
    }

    private fun preCandidates(products: List<Product>, tickers: Map<String, Ticker>): List<Ticker> {
        fun clamp(v: Double) = v.coerceIn(0.0, 1.0)
        return products.mapNotNull { tickers[it.id] }.filter { t ->
            val mid = if (t.bid > 0 && t.ask >= t.bid) (t.bid + t.ask) / 2 else t.price
            val spread = if (t.bid > 0 && t.ask >= t.bid) (t.ask - t.bid) / mid else .02
            val quoteVol = t.price * max(0.0, t.volume24h)
            quoteVol >= 25_000 && spread <= .012
        }.sortedByDescending { t ->
            val mid = if (t.bid > 0 && t.ask >= t.bid) (t.bid + t.ask) / 2 else t.price
            val spread = if (t.bid > 0 && t.ask >= t.bid) (t.ask - t.bid) / mid else .02
            val quoteVol = t.price * max(0.0, t.volume24h)
            .45 * clamp((log10(quoteVol + 1) - 4.4) / 3.4) + .25 * clamp(abs(t.change24h) / .12) + .22 * clamp(1 - spread / .012) + if (t.change24h > 0) .08 else 0.0
        }
    }

    private fun analyze(t: Ticker, bank: Double): Pick? {
        val id = t.id.replace("/", "%2F")
        val book = book(getJson("https://api.exchange.coinbase.com/products/$id/book?level=2") as JSONObject) ?: return null
        val candles = candles(getJson("https://api.exchange.coinbase.com/products/$id/candles?granularity=60") as JSONArray) ?: return null
        val hist = history(t.id)
        val hollow = when { book.topShare < .12 -> .55; book.topShare < .22 -> .75; book.topShare < .35 -> .90; else -> 1.0 }
        val spreadQ = (1 - book.spread / .008).coerceIn(0.0, 1.0)
        val depthQ = ((log10(book.depth + 1) - 3.8) / 3.0).coerceIn(0.0, 1.0)
        val quoteVol = t.price * max(0.0, t.volume24h)
        val overheat = when { candles.rsi > 82 -> .72; candles.rsi < 18 -> .80; else -> 1.0 }
        val quality = ((.34 * hollow + .26 * spreadQ + .22 * depthQ + .18 * (log10(quoteVol + 1) / 8).coerceIn(0.0, 1.0)) * overheat).coerceIn(0.0, 1.0)
        val plans = linkedMapOf<Int, HorizonPlan>()
        listOf(1, 5, 15).forEach { mins ->
            val d = direction(mins, book, candles, t, hist)
            val hp = target(mins, book, candles, d, quality, hist)
            var stop = book.mid * (1 - max(candles.atr * 1.8, if (mins == 1) .0025 else .005))
            if (book.support < book.mid && book.support > stop && book.supportStrength >= 2.2) stop = book.support * (1 - .0015)
            val plan = bankPlan(bank, book, hp)
            val strength = abs(d) * 100 * quality
            val score = rank(plan, d > .10, strength, quality, hist)
            plans[mins] = HorizonPlan(mins, hp, stop, score, plan.net, plan.rr, plan.entry, plan.slip, plan.valid)
        }
        return Pick(t.id, book.mid, candles.rsi, candles.atr, hist?.ret30d ?: 0.0, plans)
    }

    private fun direction(mins: Int, b: Book, c: Candle, t: Ticker, h: History?): Double {
        fun cl(v: Double) = v.coerceIn(-1.0, 1.0)
        val rsi = cl((c.rsi - 50) / 35); val day = cl(t.change24h / .10); val h24 = cl((h?.ret24 ?: 0.0) / .08); val h7 = cl((h?.ret7d ?: 0.0) / .18); val h30 = cl((h?.ret30d ?: 0.0) / .35)
        return when (mins) {
            1 -> cl(.48*b.imbalance + .27*cl(c.trend1/.004) + .10*cl(c.trend5/.01) + .10*rsi + .05*h24)
            5 -> cl(.36*b.imbalance + .22*cl(c.trend5/.012) + .12*cl(c.trend15/.025) + .10*rsi + .10*h24 + .06*h7 + .04*day)
            else -> cl(.24*b.imbalance + .16*cl(c.trend5/.012) + .18*cl(c.trend15/.025) + .08*rsi + .12*h24 + .12*h7 + .07*h30 + .03*day)
        }
    }

    private fun target(mins: Int, b: Book, c: Candle, d: Double, q: Double, h: History?): Double {
        val trend = when (mins) { 1 -> c.trend1; 5 -> c.trend5; else -> c.trend15 }
        val floor = when (mins) { 1 -> .0007; 5 -> .0015; else -> .003 }
        val cap = when (mins) { 1 -> .018; 5 -> .04; else -> .075 }
        val histMove = (h?.abs90 ?: 0.0) * sqrt(mins / 60.0) * (h?.expansion ?: 1.0).coerceIn(.65, 2.2)
        val base = max(floor, max(c.atr * sqrt(mins.toDouble()) * .68, max(abs(trend) * .58, histMove * .75)))
        val move = (base * (.52 + max(0.0, d) * 1.08) * (.80 + q * .42)).coerceIn(floor * .7, cap)
        var target = b.mid * (1 + move)
        if (b.resistance > b.mid * 1.0006 && b.resistance < target && b.resistanceStrength >= 2.2) target = b.resistance
        return target
    }

    private fun fillBuy(asks: List<Level>, bank: Double): Fill? {
        val budget = bank / (1 + fee); var rem = budget; var cost = 0.0; var qty = 0.0
        for (l in asks) { val cap = l.price * l.qty; val take = min(rem, cap); if (take <= 0) break; qty += take / l.price; cost += take; rem -= take; if (rem <= 1e-7) break }
        if (rem > max(.01, budget * .001) || qty <= 0) return null
        return Fill(qty, cost / qty, cost * fee)
    }

    private fun bankPlan(bank: Double, b: Book, target: Double): Plan {
        val f = fillBuy(b.asks, bank) ?: return Plan(false, -999.0, 999.0, -99.0, 0.0, 1.0)
        val slip = max(0.0, (f.avgEntry - b.ask) / b.ask)
        val exitDrag = max(b.spread / 2, slip * .7)
        val gross = f.qty * target * (1 - exitDrag)
        val net = gross * (1 - fee) - bank
        val stop = b.support.coerceAtMost(b.mid * .995)
        val stopNet = f.qty * stop * (1 - exitDrag) * (1 - fee)
        val loss = max(0.0, bank - stopNet)
        return Plan(true, net, loss, if (loss > 0) net / loss else 0.0, f.avgEntry, slip)
    }

    private fun rank(p: Plan, bullish: Boolean, strength: Double, quality: Double, h: History?): Int {
        if (!p.valid || !bullish) return 0
        val edge = (p.net / max(1.0, abs(p.net) + 100.0) * 8).coerceIn(-1.0, 1.0)
        val rr = (p.rr / 2).coerceIn(0.0, 1.0)
        val slipPenalty = (p.slip / .01).coerceIn(0.0, 1.0) * 18
        val expBonus = ((h?.expansion ?: 1.0) - 1).times(12).coerceIn(-8.0, 12.0)
        return (strength*.50 + quality*100*.20 + max(0.0, edge)*100*.18 + rr*100*.12 + expBonus - if (p.net <= 0) 30 else 0 - slipPenalty).roundToInt().coerceIn(0,100)
    }

    private fun book(j: JSONObject): Book? {
        fun levels(name: String): List<Level> { val a=j.optJSONArray(name)?:return emptyList(); return (0 until min(120,a.length())).mapNotNull { i -> val x=a.optJSONArray(i)?:return@mapNotNull null; val p=x.optString(0).toDoubleOrNull()?:0.0; val q=x.optString(1).toDoubleOrNull()?:0.0; if(p>0&&q>0) Level(p,q) else null } }
        val bids=levels("bids"); val asks=levels("asks"); if(bids.isEmpty()||asks.isEmpty()) return null
        val bid=bids.first().price; val ask=asks.first().price; val mid=(bid+ask)/2; val spread=(ask-bid)/mid
        val bn=bids.take(50).map{it.price*it.qty}; val an=asks.take(50).map{it.price*it.qty}; val bd=bn.sum(); val ad=an.sum(); val imb=(bd-ad)/(bd+ad).coerceAtLeast(1e-9)
        fun med(v:List<Double>):Double{val s=v.sorted();return if(s.isEmpty())1.0 else s[s.size/2].coerceAtLeast(1e-9)}
        val support=bids.filter{it.price>=mid*.985}.maxByOrNull{it.price*it.qty}?:bids.first(); val resistance=asks.filter{it.price<=mid*1.015}.maxByOrNull{it.price*it.qty}?:asks.first()
        return Book(bids,asks,bid,ask,mid,spread,imb,(bn.take(5).sum()+an.take(5).sum())/(bd+ad).coerceAtLeast(1e-9),bd+ad,support.price,resistance.price,support.price*support.qty/med(bn),resistance.price*resistance.qty/med(an))
    }

    private fun candles(a: JSONArray): Candle? {
        data class C(val t:Long,val l:Double,val h:Double,val c:Double)
        val rows=(0 until a.length()).mapNotNull{i->val x=a.optJSONArray(i)?:return@mapNotNull null; val c=x.optDouble(4); if(c>0) C(x.optLong(0),x.optDouble(1),x.optDouble(2),c) else null}.sortedBy{it.t}
        if(rows.size<20)return null
        val close=rows.map{it.c}; val last=close.last(); fun tr(n:Int):Double{val b=close[(close.size-1-n).coerceAtLeast(0)];return if(b>0)(last-b)/b else 0.0}
        var gains=0.0;var losses=0.0;for(i in close.size-14 until close.size){val d=close[i]-close[i-1];if(d>=0)gains+=d else losses-=d}
        val rsi=if(losses==0.0)100.0 else 100-100/(1+(gains/14)/(losses/14));var range=0.0
        for(i in rows.size-14 until rows.size){val p=rows[i-1].c;range+=max(rows[i].h-rows[i].l,max(abs(rows[i].h-p),abs(rows[i].l-p)))}
        return Candle(rsi,(range/14)/last,tr(1),tr(5),tr(15))
    }

    private fun history(product: String): History? {
        val now=System.currentTimeMillis()/1000; val all=mutableListOf<JSONArray>()
        for(k in 3 downTo 1){val start=now-k*10*86400;val end=start+10*86400;try{val a=getJson("https://api.exchange.coinbase.com/products/$product/candles?granularity=3600&start=${java.time.Instant.ofEpochSecond(start)}&end=${java.time.Instant.ofEpochSecond(end)}") as JSONArray;for(i in 0 until a.length())all+=a.getJSONArray(i)}catch(_:Exception){}}
        if(all.size<72)return null
        val rows=all.distinctBy{it.optLong(0)}.sortedBy{it.optLong(0)};val close=rows.map{it.optDouble(4)};val last=close.last();fun ret(n:Int):Double{val b=close[(close.size-1-n).coerceAtLeast(0)];return if(b>0)(last-b)/b else 0.0}
        val rs=(1 until close.size).map{if(close[it-1]>0)(close[it]-close[it-1])/close[it-1] else 0.0};val abs=rs.map{kotlin.math::abs}.sorted();val p90=if(abs.isEmpty())0.0 else abs[((abs.size-1)*.9).toInt()];val recent=rs.takeLast(24).map{kotlin.math::abs}.average();val old=rs.dropLast(24).takeLast(24*14).map{kotlin.math::abs};val older=if(old.isEmpty())recent else old.average();return History(ret(24),ret(168),ret(min(719,close.size-1)),p90,if(older>0)recent/older else 1.0)
    }
}
