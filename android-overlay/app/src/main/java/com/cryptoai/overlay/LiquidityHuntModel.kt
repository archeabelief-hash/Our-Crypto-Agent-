package com.cryptoai.overlay

import java.util.ArrayDeque
import kotlin.math.*

class LiquidityHuntModel {
    data class Result(
        val zoneLow: Double,
        val zoneHigh: Double,
        val sniperEntry: Double,
        val abortBelow: Double,
        val bounceTarget: Double,
        val sweepProbability: Int,
        val absorption: Int,
        val impactAbsorption: Int,
        val breakdownGuard: Int,
        val action: String,
        val reason: String
    )

    private data class Delta(val t: Long, val side: String, val add: Double, val remove: Double)
    private data class Flow(val t: Long, val buy: Double, val sell: Double)
    private data class Px(val t: Long, val mid: Double)

    private val deltas = ArrayDeque<Delta>()
    private val flows = ArrayDeque<Flow>()
    private val prices = ArrayDeque<Px>()

    @Synchronized fun onBookUpdate(side: String, oldQty: Double, newQty: Double, now: Long) {
        val add = max(0.0, newQty - oldQty)
        val remove = max(0.0, oldQty - newQty)
        deltas.addLast(Delta(now, side, add, remove))
        trim(now)
    }

    @Synchronized fun onTrade(size: Double, aggressiveBuy: Boolean, now: Long) {
        if (size <= 0.0) return
        flows.addLast(Flow(now, if (aggressiveBuy) size else 0.0, if (aggressiveBuy) 0.0 else size))
        trim(now)
    }

    @Synchronized fun evaluate(
        bid: Double,
        ask: Double,
        bids: Map<Double, Double>,
        asks: Map<Double, Double>,
        support: Double,
        resistance: Double,
        momentum: Double,
        crossMomentum: Double,
        atrFloorPct: Double = 0.0012
    ): Result {
        val now = System.currentTimeMillis()
        trim(now)
        val mid = (bid + ask) / 2.0
        if (mid <= 0.0) return Result(0.0,0.0,0.0,0.0,0.0,0,0,0,0,"WAIT","Waiting for price")
        prices.addLast(Px(now, mid)); trim(now)

        val last3500 = flows.filter { now - it.t <= 3500 }
        val last1000 = flows.filter { now - it.t <= 1000 }
        fun normalizedFlow(xs: List<Flow>): Double {
            val b = xs.sumOf { it.buy }; val s = xs.sumOf { it.sell }
            return if (b + s <= 0.0) 0.0 else (b - s) / (b + s)
        }
        val f35 = normalizedFlow(last3500)
        val f1 = normalizedFlow(last1000)
        val sell35 = last3500.sumOf { it.sell }
        val sell1 = last1000.sumOf { it.sell }

        val recentDelta = deltas.filter { now - it.t <= 1200 }
        val bidAdd = recentDelta.filter { it.side == "bid" }.sumOf { it.add }
        val bidRemove = recentDelta.filter { it.side == "bid" }.sumOf { it.remove }
        val askAdd = recentDelta.filter { it.side == "offer" || it.side == "ask" }.sumOf { it.add }
        val askRemove = recentDelta.filter { it.side == "offer" || it.side == "ask" }.sumOf { it.remove }
        val replenishment = bidAdd / (bidAdd + bidRemove + 1e-9)
        val bidRemoval = bidRemove / (bidAdd + bidRemove + 1e-9)
        val askDepletion = askRemove / (askAdd + askRemove + 1e-9)

        val pNow = prices.lastOrNull()?.mid ?: mid
        val pOld = prices.firstOrNull { now - it.t <= 4500 }?.mid ?: prices.firstOrNull()?.mid ?: mid
        val p1 = prices.firstOrNull { now - it.t <= 1100 }?.mid ?: pNow
        val drop = ((pNow - pOld) / pOld).coerceIn(-0.03, 0.03)
        val drop1 = if (p1 > 0.0) ((pNow-p1)/p1).coerceIn(-0.02,0.02) else 0.0
        val older = prices.filter { now - it.t in 1800..6500 }
        val oldDrop = if (older.size >= 2) (older.last().mid - older.first().mid) / older.first().mid else drop
        val stall = if (oldDrop < -0.0002) (1.0 - abs(min(0.0, drop)) / max(abs(oldDrop), 1e-6)).coerceIn(0.0, 1.0) else 0.0

        val impactNow = abs(min(0.0, drop1)) / (sell1 + 1e-9)
        val impactBase = abs(min(0.0, drop)) / (sell35 + 1e-9)
        val impactAbsorb = if (sell1 > 0.0 && sell35 > sell1 && impactBase > 0.0) (1.0-impactNow/impactBase).coerceIn(0.0,1.0) else 0.0
        val impactAcceleration = if (sell1 > 0.0 && impactBase > 0.0) (impactNow/impactBase-1.0).coerceIn(0.0,1.0) else 0.0

        val nearBidQty = bids.filterKeys { it >= mid * .997 }.values.sum()
        val nearAskQty = asks.filterKeys { it <= mid * 1.003 }.values.sum()
        val imbalance = if (nearBidQty + nearAskQty <= 0.0) 0.0 else (nearBidQty - nearAskQty) / (nearBidQty + nearAskQty)

        val rangePct = max(atrFloorPct, max((ask - bid) / mid * 6.0, abs(drop) * 0.8))
        val pad = max(mid * .00035, mid * rangePct * .24)
        val floor = support.takeIf { it > 0.0 && it < mid * 1.01 } ?: bid
        val zoneHigh = min(mid, floor + pad * .30)
        val zoneLow = max(0.0, floor - pad * 1.15)
        val dist = ((mid - zoneHigh) / mid).coerceAtLeast(0.0)
        val approach = (1.0 - dist / max(rangePct * 1.8, .001)).coerceIn(0.0, 1.0)
        val sellPressure = (-f35 * .65 + max(0.0, -drop) / .004 * .35).coerceIn(0.0, 1.0)
        val crossStall = (.5 + crossMomentum * .5).coerceIn(0.0, 1.0)
        val flowRecovery = ((f1 - min(f35, 0.0)) / 1.5 + .35).coerceIn(0.0, 1.0)
        var absorptionScore = (replenishment * .24 + askDepletion * .08 + stall * .18 + impactAbsorb * .22 + flowRecovery * .18 + crossStall * .10).coerceIn(0.0, 1.0)
        val breakdown = (impactAcceleration*.42 + max(0.0,-f1)*.33 + bidRemoval*.25).coerceIn(0.0,1.0)
        if (breakdown > .68) absorptionScore *= .62
        val sweepScore = (.16 + approach * .30 + sellPressure * .24 + max(0.0, -momentum) * .12 + max(0.0, -imbalance) * .08 + rangePct.coerceAtMost(.01) * 5.0).coerceIn(0.0, 1.0)

        val inZone = mid <= zoneHigh && mid >= zoneLow
        val below = mid < zoneLow
        val earlyScore = (absorptionScore * .70 + (if (inZone) .18 else 0.0) + (if (f1 > -.12) .12 else 0.0)).coerceIn(0.0, 1.0)
        val sniper = zoneLow + (zoneHigh - zoneLow) * .58
        val abort = max(0.0, zoneLow - pad * .85)
        val bounce = max(mid + mid * rangePct * .95, min(resistance.takeIf { it > mid } ?: mid * (1 + rangePct * 2.0), mid + mid * rangePct * 2.2))

        val action: String
        val reason: String
        when {
            (below && f1 < -.25) || (inZone && breakdown >= .82) -> { action = "ABORT — BREAKDOWN STILL ACCELERATING"; reason = "Selling is still producing efficient downside movement and/or bids are being removed." }
            inZone && earlyScore >= .74 && breakdown < .58 -> { action = "EARLY REVERSAL WINDOW"; reason = "Sweep zone reached while sell impact fades, bids replenish, and breakdown risk has eased." }
            inZone -> { action = "SWEEP IN PROGRESS — WATCH CLOSELY"; reason = "Price is inside the liquidity pocket; waiting for lower downside impact and stronger exhaustion." }
            sweepScore >= .70 -> { action = "PREPARE SNIPER ENTRY"; reason = "Book structure points toward a probable flush; reversal entry still requires the breakdown guard to clear." }
            else -> { action = "WAIT — TRACKING LIQUIDITY"; reason = "No high-confidence stop-sweep entry yet." }
        }

        return Result(zoneLow, zoneHigh, sniper, abort, bounce,
            (sweepScore*100).roundToInt(), (absorptionScore*100).roundToInt(),
            (impactAbsorb*100).roundToInt(), (breakdown*100).roundToInt(), action, reason)
    }

    private fun trim(now: Long) {
        while (deltas.isNotEmpty() && now - deltas.first().t > 12_000) deltas.removeFirst()
        while (flows.isNotEmpty() && now - flows.first().t > 12_000) flows.removeFirst()
        while (prices.isNotEmpty() && now - prices.first().t > 12_000) prices.removeFirst()
    }
}
