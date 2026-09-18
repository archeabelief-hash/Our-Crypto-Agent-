package com.cryptoai.overlay

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MarketEngine(private val product: String, private val update: (Snapshot) -> Unit) {
    data class Snapshot(
        val product: String,
        val bid: Double,
        val ask: Double,
        val imbalance: Double,
        val spoofRisk: Int,
        val signal: String,
        val confidence: Int,
        val target: Double,
        val invalidation: Double,
        val status: String,
        val buyLow: Double,
        val buyHigh: Double,
        val support: Double,
        val resistance: Double,
        val momentum: Double,
        val crossVenueMomentum: Double,
        val spreadPct: Double,
        val sourcesLive: Int,
        val sourcesTotal: Int,
        val reason: String,
        val liquidity: LiquidityHuntModel.Result,
        val actor: ActorStateModel.Result,
        val ethPerp: EthPerpTimingModel.Result?
    )

    private data class VenueState(
        var bid: Double = 0.0,
        var ask: Double = 0.0,
        var lastMs: Long = 0L,
        val history: ArrayDeque<Pair<Long, Double>> = ArrayDeque()
    )

    private val bids = sortedMapOf<Double, Double>(compareByDescending { it })
    private val asks = sortedMapOf<Double, Double>()
    private val venues = linkedMapOf(
        "Coinbase" to VenueState(),
        "Kraken" to VenueState(),
        "OKX" to VenueState(),
        "Binance" to VenueState()
    )
    private val bookEvents = ArrayDeque<Pair<Long, Boolean>>()
    private val tradeEvents = ArrayDeque<Triple<Long, Double, Boolean>>()
    private val liquidityHunt = LiquidityHuntModel()
    private val actorState = ActorStateModel()
    private val ethPerpTiming = EthPerpTimingModel()
    private val btcHistory = ArrayDeque<Pair<Long, Double>>()
    @Volatile private var btcPrice = 0.0
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val sockets = mutableListOf<WebSocket>()
    private var stopped = false

    private val base = product.substringBefore("-").uppercase(Locale.US)
    private val quote = product.substringAfter("-", "USD").uppercase(Locale.US)

    fun start() {
        RemoteStrategy.current()
        startCoinbase()
        startKraken()
        startOkx()
        startBinance()
        if (base == "ETH") startBitcoinLead()
    }

    fun stop() {
        stopped = true
        synchronized(sockets) { sockets.forEach { it.close(1000, "stop") }; sockets.clear() }
    }

    private fun open(url: String, listener: WebSocketListener) {
        if (stopped) return
        val socket = client.newWebSocket(Request.Builder().url(url).build(), listener)
        synchronized(sockets) { sockets += socket }
    }

    private fun startCoinbase() {
        open("wss://advanced-trade-ws.coinbase.com", object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                listOf("level2", "market_trades", "heartbeats").forEach { channel ->
                    w.send(JSONObject().put("type", "subscribe").put("product_ids", JSONArray().put(product)).put("channel", channel).toString())
                }
            }
            override fun onMessage(w: WebSocket, text: String) { try { parseCoinbase(JSONObject(text)) } catch (_: Exception) {} }
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) { emit("Coinbase reconnecting") }
        })
    }

    private fun startBitcoinLead() {
        open("wss://advanced-trade-ws.coinbase.com", object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(
                    JSONObject()
                        .put("type", "subscribe")
                        .put("product_ids", JSONArray().put("BTC-USD"))
                        .put("channel", "ticker")
                        .toString()
                )
            }

            override fun onMessage(w: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    if (j.optString("channel") != "ticker") return
                    val events = j.optJSONArray("events") ?: return
                    val now = System.currentTimeMillis()
                    for (i in 0 until events.length()) {
                        val tickers = events.getJSONObject(i).optJSONArray("tickers") ?: continue
                        for (k in 0 until tickers.length()) {
                            val t = tickers.getJSONObject(k)
                            if (t.optString("product_id") != "BTC-USD") continue
                            val bid = t.optString("best_bid").toDoubleOrNull() ?: 0.0
                            val ask = t.optString("best_ask").toDoubleOrNull() ?: 0.0
                            val price = t.optString("price").toDoubleOrNull() ?: 0.0
                            val mid = if (bid > 0.0 && ask >= bid) (bid + ask) / 2.0 else price
                            if (mid > 0.0) {
                                btcPrice = mid
                                synchronized(btcHistory) {
                                    if (btcHistory.isEmpty() || now - btcHistory.last().first >= 200L) {
                                        btcHistory.addLast(now to mid)
                                    }
                                    while (btcHistory.isNotEmpty() && now - btcHistory.first().first > 90_000L) {
                                        btcHistory.removeFirst()
                                    }
                                }
                                emit()
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        })
    }

    private fun btcMove(seconds: Int): Double {
        val now = System.currentTimeMillis()
        synchronized(btcHistory) {
            if (btcHistory.size < 2) return 0.0
            val cutoff = now - seconds * 1000L
            val first = btcHistory.firstOrNull { it.first >= cutoff } ?: btcHistory.first()
            val last = btcHistory.last()
            if (first.second <= 0.0) return 0.0
            return ((last.second - first.second) / first.second).coerceIn(-0.03, 0.03)
        }
    }

    private fun startKraken() {
        val symbol = "$base/${if (quote == "USDC") "USD" else quote}"
        open("wss://ws.kraken.com/v2", object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("method", "subscribe").put("params", JSONObject().put("channel", "ticker").put("symbol", JSONArray().put(symbol)).put("snapshot", true)).toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    if (j.optString("channel") != "ticker") return
                    val d = j.optJSONArray("data")?.optJSONObject(0) ?: return
                    updateVenue("Kraken", d.optDouble("bid"), d.optDouble("ask"))
                } catch (_: Exception) {}
            }
        })
    }

    private fun startOkx() {
        val inst = "$base-${if (quote == "USD") "USDT" else quote}"
        open("wss://ws.okx.com:8443/ws/v5/public", object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("op", "subscribe").put("args", JSONArray().put(JSONObject().put("channel", "tickers").put("instId", inst))).toString())
            }
            override fun onMessage(w: WebSocket, text: String) {
                try {
                    val d = JSONObject(text).optJSONArray("data")?.optJSONObject(0) ?: return
                    updateVenue("OKX", d.optString("bidPx").toDoubleOrNull() ?: 0.0, d.optString("askPx").toDoubleOrNull() ?: 0.0)
                } catch (_: Exception) {}
            }
        })
    }

    private fun startBinance() {
        val symbol = (base + if (quote == "USD") "USDT" else quote).lowercase(Locale.US)
        open("wss://stream.binance.com:9443/ws/${symbol}@bookTicker", object : WebSocketListener() {
            override fun onMessage(w: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    updateVenue("Binance", j.optString("b").toDoubleOrNull() ?: 0.0, j.optString("a").toDoubleOrNull() ?: 0.0)
                } catch (_: Exception) {}
            }
        })
    }

    @Synchronized private fun parseCoinbase(j: JSONObject) {
        val channel = j.optString("channel")
        val events = j.optJSONArray("events") ?: return
        val now = System.currentTimeMillis()
        for (i in 0 until events.length()) {
            val e = events.getJSONObject(i)
            if (channel == "l2_data") {
                val updates = e.optJSONArray("updates") ?: continue
                for (k in 0 until updates.length()) {
                    val x = updates.getJSONObject(k)
                    val side = x.optString("side")
                    val price = x.optDouble("price_level")
                    val qty = x.optDouble("new_quantity")
                    if (price <= 0) continue
                    val book = if (side == "bid") bids else asks
                    val old = book[price] ?: 0.0
                    liquidityHunt.onBookUpdate(side, old, qty, now)
                    actorState.onBookUpdate(side, price, old, qty, now)
                    val cancel = old > 0.0 && qty == 0.0
                    bookEvents.addLast(now to cancel)
                    if (qty == 0.0) book.remove(price) else book[price] = qty
                }
                trimWindows(now)
                if (bids.isNotEmpty() && asks.isNotEmpty()) updateVenueInternal("Coinbase", bids.firstKey(), asks.firstKey(), now)
                emit()
            }
            if (channel == "market_trades") {
                val trades = e.optJSONArray("trades") ?: continue
                for (k in 0 until trades.length()) {
                    val t = trades.getJSONObject(k)
                    val size = t.optDouble("size")
                    if (size <= 0) continue
                    val aggressiveBuy = t.optString("side").equals("SELL", true)
                    tradeEvents.addLast(Triple(now, size, aggressiveBuy))
                    liquidityHunt.onTrade(size, aggressiveBuy, now)
                    actorState.onTrade(size, aggressiveBuy, now)
                }
                trimWindows(now)
                emit()
            }
        }
    }

    @Synchronized private fun updateVenue(name: String, bid: Double, ask: Double) {
        if (bid <= 0 || ask <= 0 || ask < bid) return
        updateVenueInternal(name, bid, ask, System.currentTimeMillis())
        emit()
    }

    private fun updateVenueInternal(name: String, bid: Double, ask: Double, now: Long) {
        val v = venues[name] ?: return
        v.bid = bid; v.ask = ask; v.lastMs = now
        val mid = (bid + ask) / 2.0
        if (v.history.isEmpty() || now - v.history.last().first >= 250) v.history.addLast(now to mid)
        val keepMs = RemoteStrategy.current().momentumWindowSeconds.coerceAtLeast(5) * 2000L
        while (v.history.isNotEmpty() && now - v.history.first().first > keepMs) v.history.removeFirst()
    }

    private fun trimWindows(now: Long) {
        val window = 10_000L
        while (bookEvents.isNotEmpty() && now - bookEvents.first().first > window) bookEvents.removeFirst()
        while (tradeEvents.isNotEmpty() && now - tradeEvents.first().first > window) tradeEvents.removeFirst()
    }

    @Synchronized private fun emit(statusOverride: String? = null) {
        if (bids.isEmpty() || asks.isEmpty()) return
        val cfg = RemoteStrategy.current()
        val now = System.currentTimeMillis()
        trimWindows(now)

        val bid = bids.firstKey()
        val ask = asks.firstKey()
        val mid = (bid + ask) / 2.0
        val spreadPct = if (mid > 0) (ask - bid) / mid else 0.0

        val depthBids = bids.filterKeys { it >= mid * (1 - cfg.depthPct) }
        val depthAsks = asks.filterKeys { it <= mid * (1 + cfg.depthPct) }
        val bidDepth = depthBids.values.sum()
        val askDepth = depthAsks.values.sum()
        val imbalance = if (bidDepth + askDepth == 0.0) 0.0 else (bidDepth - askDepth) / (bidDepth + askDepth)

        val buys = tradeEvents.filter { it.third }.sumOf { it.second }
        val sells = tradeEvents.filter { !it.third }.sumOf { it.second }
        val flow = if (buys + sells == 0.0) 0.0 else (buys - sells) / (buys + sells)

        val bestBidQty = bids[bid] ?: 0.0
        val bestAskQty = asks[ask] ?: 0.0
        val microPressure = if (bestBidQty + bestAskQty == 0.0) 0.0 else (bestBidQty - bestAskQty) / (bestBidQty + bestAskQty)

        val cancelRatio = if (bookEvents.isEmpty()) 0.0 else bookEvents.count { it.second }.toDouble() / bookEvents.size
        val sample = (bids.values.take(20) + asks.values.take(20)).sorted()
        val median = if (sample.isEmpty()) 1.0 else sample[sample.size / 2].coerceAtLeast(1e-9)
        val largestWall = max(bids.values.take(20).maxOrNull() ?: 0.0, asks.values.take(20).maxOrNull() ?: 0.0)
        val wallRatio = largestWall / median
        val spoof = (cancelRatio * cfg.cancelWeight + min(1.0, wallRatio / cfg.wallScale) * cfg.wallWeight).roundToInt().coerceIn(0, 100)

        val coinbaseMomentum = momentumFor(venues.getValue("Coinbase"), cfg.momentumWindowSeconds)
        val liveOthers = venues.filter { (name, v) -> name != "Coinbase" && now - v.lastMs < 15_000 && v.bid > 0 && v.ask > 0 }
        val crossVenueMomentum = if (liveOthers.isEmpty()) 0.0 else liveOthers.values.map { momentumFor(it, cfg.momentumWindowSeconds) }.average().coerceIn(-1.0, 1.0)
        val sourcesLive = venues.count { (_, v) -> now - v.lastMs < 15_000 && v.bid > 0 && v.ask > 0 }

        var score = imbalance * cfg.imbalanceWeight + flow * cfg.flowWeight + coinbaseMomentum * cfg.momentumWeight + crossVenueMomentum * cfg.venueWeight + microPressure * cfg.microPriceWeight
        score = score.coerceIn(-1.0, 1.0)
        if (spreadPct > cfg.maxSpreadPct) score *= 0.45
        if (spoof >= cfg.maxSpoofRisk) score *= 0.55

        val confidenceBoost = if (sourcesLive >= 3) 1.08 else if (sourcesLive == 1) 0.82 else 1.0
        val confidence = (abs(score) * 100.0 * confidenceBoost).roundToInt().coerceIn(0, cfg.confidenceCap)

        val baseSignal = when {
            spreadPct > cfg.maxSpreadPct -> "WAIT — SPREAD TOO WIDE"
            spoof >= cfg.maxSpoofRisk -> "WAIT — BOOK UNSTABLE"
            score >= cfg.buyNowThreshold && confidence >= 45 -> "BUY NOW"
            score <= cfg.sellNowThreshold && confidence >= 45 -> "SELL NOW"
            score >= cfg.watchThreshold -> "GET READY TO BUY"
            score <= -cfg.watchThreshold -> "GET READY TO SELL"
            else -> "WAIT"
        }

        val support = depthBids.maxByOrNull { it.value }?.key ?: bid
        val resistance = depthAsks.maxByOrNull { it.value }?.key ?: ask
        val range = (ask - bid).coerceAtLeast(mid * cfg.rangeFloorPct)
        val hunt = liquidityHunt.evaluate(bid, ask, bids, asks, support, resistance, coinbaseMomentum, crossVenueMomentum, max(cfg.rangeFloorPct, spreadPct * 6.0))
        val actor = actorState.evaluate(mid, bids, asks)
        val signal = when {
            hunt.action.startsWith("ABORT") -> "SELL NOW — SWEEP FAILED"
            hunt.action.startsWith("EARLY REVERSAL") && spoof < cfg.maxSpoofRisk -> "BUY NOW — EARLY REVERSAL"
            hunt.action.startsWith("PREPARE SNIPER") -> "GET READY TO BUY — SWEEP ZONE"
            actor.state == "RELEASING" && actor.confidence >= 70 && score > 0 -> "BUY NOW — ACTOR RELEASE"
            else -> baseSignal
        }
        val buyLow = if (hunt.zoneLow > 0) hunt.zoneLow else max(support, mid - range * 2.0).coerceAtMost(ask)
        val buyHigh = if (hunt.zoneHigh > 0) hunt.zoneHigh else ask
        val rawTarget = mid + range * cfg.targetMultiple
        val target = max(hunt.bounceTarget, if (resistance > ask * 1.0004 && resistance < rawTarget * 1.02) resistance else rawTarget)
        val rawStop = mid - range * cfg.invalidationMultiple
        val invalidation = if (hunt.abortBelow > 0) hunt.abortBelow else min(rawStop, support - range * 0.75).coerceAtLeast(0.0)

        val reasons = mutableListOf<String>()
        reasons += hunt.reason
        reasons += "actor ${actor.state.lowercase(Locale.US)} ${actor.confidence}%"
        if (imbalance > 0.15) reasons += "buyers heavier in book" else if (imbalance < -0.15) reasons += "sellers heavier in book"
        if (flow > 0.15) reasons += "buyers hitting market" else if (flow < -0.15) reasons += "selling pressure"
        if (sourcesLive >= 3 && crossVenueMomentum > 0.10) reasons += "other exchanges confirm up" else if (sourcesLive >= 3 && crossVenueMomentum < -0.10) reasons += "other exchanges confirm down"
        if (spoof >= 55) reasons += "order book cancellation risk elevated"
        val reason = reasons.take(4).joinToString(" • ")

        val ethPerp = if (base == "ETH") {
            ethPerpTiming.evaluate(
                bid = bid,
                ask = ask,
                bids = bids,
                asks = asks,
                imbalance = imbalance,
                flow = flow,
                ethMomentum = coinbaseMomentum,
                crossVenueMomentum = crossVenueMomentum,
                spoofRisk = spoof,
                btcPrice = btcPrice,
                btcMove15 = btcMove(15),
                btcMove60 = btcMove(60),
                fallbackSupport = support,
                fallbackResistance = resistance
            )
        } else null

        update(Snapshot(product, bid, ask, imbalance, spoof, signal, confidence, target, invalidation,
            statusOverride ?: "LIVE • $sourcesLive/${venues.size} feeds • LIQUIDITY + ACTOR STATE",
            buyLow, buyHigh, support, resistance, coinbaseMomentum, crossVenueMomentum,
            spreadPct, sourcesLive, venues.size, reason, hunt, actor, ethPerp))
    }

    private fun momentumFor(v: VenueState, seconds: Long): Double {
        if (v.history.size < 2) return 0.0
        val now = System.currentTimeMillis()
        val cutoff = now - seconds.coerceAtLeast(5) * 1000L
        val first = v.history.firstOrNull { it.first >= cutoff } ?: v.history.first()
        val last = v.history.last()
        if (first.second <= 0) return 0.0
        val pct = (last.second - first.second) / first.second
        return (pct / 0.003).coerceIn(-1.0, 1.0)
    }
}
