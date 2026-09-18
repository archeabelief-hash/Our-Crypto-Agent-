package com.cryptoai.overlay

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * ETH-focused timing model for manual perpetual trades.
 * It does not place orders. It combines the ETH order book, aggressive trade flow,
 * short-term ETH momentum, cross-venue confirmation and BTC lead momentum.
 */
class EthPerpTimingModel {
    data class Shelf(
        val price: Double,
        val strength: Double,
        val distancePct: Double
    )

    data class Result(
        val signal: String,
        val confidence: Int,
        val btcRead: String,
        val btcPrice: Double,
        val btcMove15: Double,
        val btcMove60: Double,
        val score: Double,
        val scoreChange: Double,
        val longEntryLow: Double,
        val longEntryHigh: Double,
        val longTarget: Double,
        val longStop: Double,
        val shortEntryLow: Double,
        val shortEntryHigh: Double,
        val shortTarget: Double,
        val shortStop: Double,
        val supports: List<Shelf>,
        val resistances: List<Shelf>,
        val reason: String
    )

    private val scoreHistory = ArrayDeque<Pair<Long, Double>>()

    fun evaluate(
        bid: Double,
        ask: Double,
        bids: Map<Double, Double>,
        asks: Map<Double, Double>,
        imbalance: Double,
        flow: Double,
        ethMomentum: Double,
        crossVenueMomentum: Double,
        spoofRisk: Int,
        btcPrice: Double,
        btcMove15: Double,
        btcMove60: Double,
        fallbackSupport: Double,
        fallbackResistance: Double
    ): Result {
        val now = System.currentTimeMillis()
        val mid = (bid + ask) / 2.0
        if (mid <= 0.0) {
            return Result(
                "WAIT — NO ETH PRICE", 0, "BTC waiting", btcPrice, btcMove15, btcMove60,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                emptyList(), emptyList(), "Waiting for live ETH data."
            )
        }

        val spreadPct = ((ask - bid) / mid).coerceAtLeast(0.0)
        val supports = shelfMap(bids, mid, true)
        val resistances = shelfMap(asks, mid, false)
        val nearestSupport = supports.maxByOrNull { it.price }?.price ?: fallbackSupport.coerceAtMost(mid)
        val nearestResistance = resistances.minByOrNull { it.price }?.price ?: fallbackResistance.coerceAtLeast(mid)

        val btcNorm = (btcMove15 / 0.0025).coerceIn(-1.0, 1.0)
        val btcSlowNorm = (btcMove60 / 0.0050).coerceIn(-1.0, 1.0)
        var score = (
            0.29 * imbalance +
            0.22 * flow +
            0.19 * ethMomentum +
            0.12 * crossVenueMomentum +
            0.12 * btcNorm +
            0.06 * btcSlowNorm
        ).coerceIn(-1.0, 1.0)

        if (spoofRisk >= 70) score *= 0.58
        if (spreadPct > 0.0025) score *= 0.70

        scoreHistory.addLast(now to score)
        while (scoreHistory.isNotEmpty() && now - scoreHistory.first().first > 8_000L) {
            scoreHistory.removeFirst()
        }
        val oldScore = scoreHistory.firstOrNull { now - it.first >= 2_500L }?.second
            ?: scoreHistory.firstOrNull()?.second ?: score
        val scoreChange = (score - oldScore).coerceIn(-1.0, 1.0)

        val supportDistance = ((mid - nearestSupport) / mid).coerceAtLeast(0.0)
        val resistanceDistance = ((nearestResistance - mid) / mid).coerceAtLeast(0.0)
        val supportStrength = supports.maxByOrNull { it.price }?.strength ?: 1.0
        val resistanceStrength = resistances.minByOrNull { it.price }?.strength ?: 1.0

        val longEntryLow = nearestSupport
        val longEntryHigh = min(mid, nearestSupport * 1.0016).coerceAtLeast(nearestSupport)
        val shortEntryHigh = nearestResistance
        val shortEntryLow = max(mid, nearestResistance * 0.9984).coerceAtMost(nearestResistance)

        val nextResistanceAbove = resistances
            .filter { it.price > max(mid, nearestResistance * 1.0002) }
            .minByOrNull { it.price }?.price
        val nextSupportBelow = supports
            .filter { it.price < min(mid, nearestSupport * 0.9998) }
            .maxByOrNull { it.price }?.price

        val fallbackMove = max(0.0022, spreadPct * 8.0)
        val longTarget = when {
            nearestResistance > mid * 1.0010 -> nearestResistance
            nextResistanceAbove != null -> nextResistanceAbove
            else -> mid * (1.0 + fallbackMove)
        }
        val shortTarget = when {
            nearestSupport < mid * 0.9990 -> nearestSupport
            nextSupportBelow != null -> nextSupportBelow
            else -> mid * (1.0 - fallbackMove)
        }

        val longStopBase = nextSupportBelow ?: nearestSupport
        val shortStopBase = nextResistanceAbove ?: nearestResistance
        val longStop = max(0.0, longStopBase * 0.9984)
        val shortStop = shortStopBase * 1.0016

        val btcRead = when {
            btcPrice <= 0.0 -> "BTC WAITING — no lead data yet"
            btcMove15 >= 0.0015 && btcMove60 >= 0.0 -> "BTC PUSHING UP — supports ETH longs"
            btcMove15 <= -0.0015 && btcMove60 <= 0.0 -> "BTC PUSHING DOWN — supports ETH shorts"
            btcMove15 > 0.0004 -> "BTC LEANING UP"
            btcMove15 < -0.0004 -> "BTC LEANING DOWN"
            else -> "BTC MOSTLY FLAT"
        }

        val btcConfirmsLong = btcPrice <= 0.0 || btcMove15 > -0.0006
        val btcConfirmsShort = btcPrice <= 0.0 || btcMove15 < 0.0006
        val enoughRoomUp = (longTarget - mid) / mid >= 0.0012
        val enoughRoomDown = (mid - shortTarget) / mid >= 0.0012
        val nearFloor = supportDistance <= 0.0018
        val nearCeiling = resistanceDistance <= 0.0018

        val signal = when {
            spoofRisk >= 82 -> "WAIT — ORDER BOOK TOO UNSTABLE"
            score >= 0.32 && btcConfirmsLong && enoughRoomUp ->
                "LONG NOW — MOMENTUM + BTC CONFIRM"
            score <= -0.32 && btcConfirmsShort && enoughRoomDown ->
                "SHORT NOW — MOMENTUM + BTC CONFIRM"
            nearFloor && supportStrength >= 1.7 && scoreChange >= 0.08 && btcConfirmsLong ->
                "PREPARE LONG — FLOOR HOLD / REVERSAL FORMING"
            nearCeiling && resistanceStrength >= 1.7 && scoreChange <= -0.08 && btcConfirmsShort ->
                "PREPARE SHORT — CEILING REJECTION FORMING"
            score >= 0.18 && btcConfirmsLong ->
                "LONG BIAS — WAIT FOR BREAK OR FLOOR HOLD"
            score <= -0.18 && btcConfirmsShort ->
                "SHORT BIAS — WAIT FOR BREAK OR CEILING REJECT"
            else -> "WAIT — MID-RANGE / NO CLEAN EDGE"
        }

        val dataQuality = when {
            btcPrice <= 0.0 -> 0.82
            spoofRisk >= 70 -> 0.72
            else -> 1.0
        }
        val shelfBonus = when {
            (nearFloor && supportStrength >= 2.0) || (nearCeiling && resistanceStrength >= 2.0) -> 10
            else -> 0
        }
        val confidence = (
            abs(score) * 82.0 * dataQuality +
            min(12.0, abs(scoreChange) * 45.0) +
            shelfBonus
        ).roundToInt().coerceIn(0, 95)

        val reason = buildList {
            add("ETH pressure ${(score * 100).roundToInt()}")
            if (scoreChange > 0.06) add("pressure turning up")
            if (scoreChange < -0.06) add("pressure turning down")
            if (nearFloor) add("near support shelf")
            if (nearCeiling) add("near resistance shelf")
            if (btcMove15 > 0.0007) add("BTC leading up")
            if (btcMove15 < -0.0007) add("BTC leading down")
            if (spoofRisk >= 55) add("wall-cancel risk elevated")
        }.joinToString(" • ")

        return Result(
            signal, confidence, btcRead, btcPrice, btcMove15, btcMove60, score, scoreChange,
            longEntryLow, longEntryHigh, longTarget, longStop,
            shortEntryLow, shortEntryHigh, shortTarget, shortStop,
            supports, resistances, reason
        )
    }

    private fun shelfMap(levels: Map<Double, Double>, mid: Double, supportSide: Boolean): List<Shelf> {
        if (mid <= 0.0 || levels.isEmpty()) return emptyList()
        val binSize = when {
            mid < 1000.0 -> 0.25
            mid < 5000.0 -> 0.50
            else -> 1.00
        }
        val bins = linkedMapOf<Long, Double>()
        for ((price, qty) in levels) {
            if (price <= 0.0 || qty <= 0.0) continue
            val distance = abs(price - mid) / mid
            if (distance > 0.018) continue
            if (supportSide && price > mid) continue
            if (!supportSide && price < mid) continue
            val key = round(price / binSize).toLong()
            bins[key] = (bins[key] ?: 0.0) + price * qty
        }
        if (bins.isEmpty()) return emptyList()
        val notionals = bins.values.sorted()
        val median = notionals[notionals.size / 2].coerceAtLeast(1e-9)
        val raw = bins.map { (key, notional) ->
            val price = key * binSize
            Shelf(
                price = price,
                strength = (notional / median).coerceIn(0.1, 20.0),
                distancePct = abs(price - mid) / mid
            )
        }
        val significant = raw.filter { it.strength >= 1.35 }
        val pool = if (significant.size >= 4) significant else raw
        return pool
            .sortedWith(compareByDescending<Shelf> { it.strength }.thenBy { it.distancePct })
            .take(6)
            .sortedBy { it.price }
    }
}
