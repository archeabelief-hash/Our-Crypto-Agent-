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
        val evidenceGrade: String
    )

    private val prefs = context.getSharedPreferences("twisted_prediction_ledger", Context.MODE_PRIVATE)
    private val key = "predictions_v3"
    private val oldKey = "predictions_v2"

    @Synchronized fun update(product: String, mid: Double, hunt: LiquidityHuntModel.Result, actor: ActorStateModel.Result): Summary {
        val now = System.currentTimeMillis()
        val rows = load()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (r.optString("product") != product || r.optString("result") != "PENDING") continue
            val lowSeen = min(r.optDouble("lowSeen", mid), mid)
            val highSeen = max(r.optDouble("highSeen", mid), mid)
            r.put("lowSeen", lowSeen).put("highSeen", highSeen)
            if (mid <= r.optDouble("zoneHigh") && !r.optBoolean("zoneTouched")) {
                r.put("zoneTouched", true).put("zoneTouchedAt", now).put("postZoneLow", mid).put("postZoneHigh", mid)
            }
            if (r.optBoolean("zoneTouched")) {
                r.put("postZoneLow", min(r.optDouble("postZoneLow", mid), mid))
                r.put("postZoneHigh", max(r.optDouble("postZoneHigh", mid), mid))
            }
            val abort = r.optDouble("abort")
            val target = r.optDouble("target")
            when {
                abort > 0 && mid <= abort -> r.put("result", "FAILED").put("resolvedAt", now).put("note", "Abort level hit before bounce objective")
                r.optBoolean("zoneTouched") && target > 0 && mid >= target -> r.put("result", "HIT").put("resolvedAt", now).put("note", "Sweep zone touched, then bounce objective reached")
                now - r.optLong("createdAt") > 20 * 60_000L -> r.put("result", "EXPIRED").put("resolvedAt", now).put("note", if (r.optBoolean("zoneTouched")) "Zone touched but bounce objective not reached in 20m" else "Sweep zone not reached in 20m")
            }
            if (r.optString("result") != "PENDING" && r.optDouble("entry") > 0.0) {
                val entry = r.optDouble("entry")
                val hi = if (r.has("postZoneHigh")) r.optDouble("postZoneHigh") else r.optDouble("highSeen")
                val lo = if (r.has("postZoneLow")) r.optDouble("postZoneLow") else r.optDouble("lowSeen")
                r.put("mfePct", (hi-entry)/entry)
                r.put("maePct", (lo-entry)/entry)
                if (r.has("zoneTouchedAt")) r.put("timeToZoneMs", r.optLong("zoneTouchedAt")-r.optLong("createdAt"))
                r.put("timeToResolveMs", r.optLong("resolvedAt")-r.optLong("createdAt"))
            }
        }

        var active = false
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (r.optString("product") == product && r.optString("result") == "PENDING") { active = true; break }
        }
        val lastCreated = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optString("product") == product }.maxOfOrNull { it.optLong("createdAt") } ?: 0L
        if (!active && hunt.sweepProbability >= 68 && now - lastCreated > 30_000L) {
            rows.put(JSONObject()
                .put("createdAt", now).put("product", product).put("result", "PENDING")
                .put("zoneLow", hunt.zoneLow).put("zoneHigh", hunt.zoneHigh)
                .put("entry", hunt.sniperEntry).put("abort", hunt.abortBelow).put("target", hunt.bounceTarget)
                .put("sweepProbability", hunt.sweepProbability)
                .put("actorState", actor.state).put("actorConfidence", actor.confidence)
                .put("zoneTouched", false).put("lowSeen", mid).put("highSeen", mid))
        }
        trim(rows)
        save(rows)
        return summarize(rows, hunt.sweepProbability)
    }

    private fun summarize(rows: JSONArray, rawProbability: Int): Summary {
        val resolvedRows = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.filter { it.optString("result") != "PENDING" }
        val resolved = resolvedRows.size
        val pending = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.count { it.optString("result") == "PENDING" }
        val zoneHits = resolvedRows.count { it.optBoolean("zoneTouched") }
        val wins = resolvedRows.count { it.optString("result") == "HIT" }
        val zoneRate = if (resolved > 0) ((zoneHits*100.0)/resolved).roundToInt() else 0
        val bounceRate = if (resolved > 0) ((wins*100.0)/resolved).roundToInt() else 0
        val floor = (wilsonLower(wins, resolved)*100).roundToInt()
        val brier = if (resolved > 0) resolvedRows.map {
            val p=it.optDouble("sweepProbability",50.0)/100.0
            val y=if(it.optBoolean("zoneTouched"))1.0 else 0.0
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
        var latestTime=0L; var latest="No resolved calls yet"
        resolvedRows.forEach { r -> val t=r.optLong("resolvedAt"); if(t>latestTime){latestTime=t;latest="${r.optString("result")} • ${r.optString("product")} • ${r.optString("note")}"} }
        val grade=when {
            resolved<30 -> "LEARNING"
            resolved<100 -> "EARLY EVIDENCE"
            floor>=55 && brier.isFinite() && brier<=.22 -> "PROMISING EDGE"
            else -> "MIXED / UNPROVEN"
        }
        return Summary(zoneRate,bounceRate,floor,calibrated,brier,
            if(mfe.isEmpty())Double.NaN else mfe.average(), if(mae.isEmpty())Double.NaN else mae.average(),
            resolved,latest,pending,grade)
    }

    private fun wilsonLower(wins:Int,n:Int,z:Double=1.645):Double {
        if(n<=0)return 0.0
        val p=wins.toDouble()/n; val z2=z*z; val d=1+z2/n
        return ((p+z2/(2*n)-z*sqrt((p*(1-p)+z2/(4*n))/n))/d).coerceIn(0.0,1.0)
    }

    private fun load(): JSONArray = try {
        val raw=prefs.getString(key,null) ?: prefs.getString(oldKey,"[]") ?: "[]"
        JSONArray(raw)
    } catch (_:Exception) { JSONArray() }
    private fun save(rows: JSONArray) { prefs.edit().putString(key, rows.toString()).apply() }
    private fun trim(rows: JSONArray) {
        if(rows.length()<=500)return
        val kept=JSONArray(); for(i in rows.length()-500 until rows.length()) kept.put(rows.get(i))
        while(rows.length()>0) rows.remove(rows.length()-1)
        for(i in 0 until kept.length()) rows.put(kept.get(i))
    }
}
