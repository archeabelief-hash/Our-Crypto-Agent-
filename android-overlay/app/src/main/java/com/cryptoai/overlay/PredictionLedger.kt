package com.cryptoai.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class PredictionLedger(context: Context) {
    data class Summary(
        val zoneHitRate: Int,
        val bounceSuccessRate: Int,
        val conservativeSuccessFloor: Int,
        val calibratedSweepProbability: Int,
        val brierScore: Double,
        val avgMfePct: Double,
        val avgMaePct: Double,
        val resolved: Int,
        val latest: String,
        val pending: Int,
        val evidenceGrade: String,
        val totalCalls: Int,
        val latestFrozen: String
    )

    private val prefs = context.getSharedPreferences("twisted_prediction_ledger", Context.MODE_PRIVATE)
    private val key = "trade_calls_v4"

    @Synchronized fun update(
        product: String,
        mid: Double,
        hunt: LiquidityHuntModel.Result,
        actor: ActorStateModel.Result,
        qualified: Boolean,
        callLabel: String,
        estimatedProfit: Double,
        rewardRisk: Double,
        bankroll: Double
    ): Summary {
        val now = System.currentTimeMillis()
        val rows = load()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (r.optString("product") != product || r.optString("result") != "PENDING") continue
            r.put("lowSeen", min(r.optDouble("lowSeen", mid), mid))
            r.put("highSeen", max(r.optDouble("highSeen", mid), mid))
            if (mid <= r.optDouble("zoneHigh") && !r.optBoolean("zoneTouched")) {
                r.put("zoneTouched", true).put("zoneTouchedAt", now)
            }
            when {
                r.optDouble("abort") > 0.0 && mid <= r.optDouble("abort") -> r.put("result", "FAILED").put("resolvedAt", now).put("note", "Abort hit before frozen target")
                r.optBoolean("zoneTouched") && r.optDouble("target") > 0.0 && mid >= r.optDouble("target") -> r.put("result", "HIT").put("resolvedAt", now).put("note", "Frozen entry/sweep zone reached, then frozen target hit")
                now - r.optLong("createdAt") > 20 * 60_000L -> r.put("result", "EXPIRED").put("resolvedAt", now).put("note", if (r.optBoolean("zoneTouched")) "Frozen entry zone touched; target not reached in 20m" else "Frozen entry zone never reached in 20m")
            }
            val entry = r.optDouble("entry")
            if (entry > 0.0) {
                r.put("mfePct", (r.optDouble("highSeen", mid) - entry) / entry)
                r.put("maePct", (r.optDouble("lowSeen", mid) - entry) / entry)
            }
        }

        val active = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.any { it.optString("product") == product && it.optString("result") == "PENDING" }
        val lastCreated = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optString("product") == product }.maxOfOrNull { it.optLong("createdAt") } ?: 0L
        if (qualified && !active && now - lastCreated > 30_000L) {
            rows.put(JSONObject()
                .put("id", "TC-$now")
                .put("kind", "TRADE_CALL")
                .put("createdAt", now).put("product", product).put("result", "PENDING")
                .put("callLabel", callLabel).put("marketPrice", mid)
                .put("zoneLow", hunt.zoneLow).put("zoneHigh", hunt.zoneHigh)
                .put("entry", hunt.sniperEntry).put("abort", hunt.abortBelow).put("target", hunt.bounceTarget)
                .put("sweepProbability", hunt.sweepProbability).put("absorption", hunt.absorption)
                .put("actorState", actor.state).put("actorConfidence", actor.confidence)
                .put("estimatedProfit", estimatedProfit).put("rewardRisk", rewardRisk).put("bankroll", bankroll)
                .put("zoneTouched", false).put("lowSeen", mid).put("highSeen", mid))
        }
        trim(rows); save(rows)
        return summarize(rows, hunt.sweepProbability)
    }

    private fun summarize(rows: JSONArray, rawProbability: Int): Summary {
        val all = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val resolvedRows = all.filter { it.optString("result") != "PENDING" }
        val resolved = resolvedRows.size
        val pending = all.count { it.optString("result") == "PENDING" }
        val zoneHits = resolvedRows.count { it.optBoolean("zoneTouched") }
        val wins = resolvedRows.count { it.optString("result") == "HIT" }
        val zoneRate = if (resolved > 0) (zoneHits * 100.0 / resolved).roundToInt() else 0
        val bounceRate = if (resolved > 0) (wins * 100.0 / resolved).roundToInt() else 0
        val floor = (wilsonLower(wins, resolved) * 100).roundToInt()
        val brier = if (resolved > 0) resolvedRows.map {
            val p = it.optDouble("sweepProbability", 50.0) / 100.0
            val y = if (it.optBoolean("zoneTouched")) 1.0 else 0.0
            (p-y).pow(2)
        }.average() else Double.NaN
        val lowBucket=(rawProbability/10)*10
        val bucket=resolvedRows.filter { it.optInt("sweepProbability") in lowBucket until (lowBucket+10) }
        val calibrated = if(bucket.isEmpty()) rawProbability else {
            val empirical=bucket.count{it.optBoolean("zoneTouched")}.toDouble()/bucket.size
            val weight=bucket.size.toDouble()/(bucket.size+30.0)
            (((rawProbability/100.0)*(1-weight)+empirical*weight)*100).roundToInt().coerceIn(2,98)
        }
        val mfe=resolvedRows.filter{it.has("mfePct")}.map{it.optDouble("mfePct")}
        val mae=resolvedRows.filter{it.has("maePct")}.map{it.optDouble("maePct")}
        val latestResolved = resolvedRows.maxByOrNull { it.optLong("resolvedAt") }
        val latest = latestResolved?.let { "${it.optString("result")} • ${it.optString("product")} • ${it.optString("note")}" } ?: "No resolved trade calls yet"
        val newest = all.maxByOrNull { it.optLong("createdAt") }
        val latestFrozen = newest?.let {
            "${it.optString("id")} • ${it.optString("callLabel")} • ${it.optString("result")}\n" +
            "Issued @ ${fmt(it.optDouble("marketPrice"))} • zone ${fmt(it.optDouble("zoneLow"))}-${fmt(it.optDouble("zoneHigh"))}\n" +
            "entry ${fmt(it.optDouble("entry"))} • abort ${fmt(it.optDouble("abort"))} • target ${fmt(it.optDouble("target"))}\n" +
            "P ${it.optInt("sweepProbability")}% • absorption ${it.optInt("absorption")}% • actor ${it.optString("actorState")} ${it.optInt("actorConfidence")}%"
        } ?: "No qualified trade call frozen yet"
        val grade=when {
            resolved<30 -> "LEARNING"
            resolved<100 -> "EARLY EVIDENCE"
            floor>=55 && brier.isFinite() && brier<=.22 -> "PROMISING EDGE"
            else -> "MIXED / UNPROVEN"
        }
        return Summary(zoneRate,bounceRate,floor,calibrated,brier,
            if(mfe.isEmpty())Double.NaN else mfe.average(), if(mae.isEmpty())Double.NaN else mae.average(),
            resolved,latest,pending,grade,all.size,latestFrozen)
    }

    private fun wilsonLower(wins:Int,n:Int,z:Double=1.645):Double {
        if(n<=0)return 0.0
        val p=wins.toDouble()/n; val z2=z*z; val d=1+z2/n
        return ((p+z2/(2*n)-z*sqrt((p*(1-p)+z2/(4*n))/n))/d).coerceIn(0.0,1.0)
    }
    private fun fmt(v:Double):String = when { v<=0||!v.isFinite()->"—"; v>=1->"%.4f".format(v); v>=.01->"%.6f".format(v); else->"%.8f".format(v) }
    private fun load(): JSONArray = try { JSONArray(prefs.getString(key,"[]") ?: "[]") } catch (_:Exception) { JSONArray() }
    private fun save(rows: JSONArray) { prefs.edit().putString(key, rows.toString()).apply() }
    private fun trim(rows: JSONArray) {
        if(rows.length()<=500)return
        val kept=JSONArray(); for(i in rows.length()-500 until rows.length()) kept.put(rows.get(i))
        while(rows.length()>0) rows.remove(rows.length()-1)
        for(i in 0 until kept.length()) rows.put(kept.get(i))
    }
}
