package com.cryptoai.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

class PredictionLedger(context: Context) {
    data class Summary(
        val zoneHitRate: Int,
        val bounceSuccessRate: Int,
        val resolved: Int,
        val latest: String,
        val pending: Int
    )

    private val prefs = context.getSharedPreferences("twisted_prediction_ledger", Context.MODE_PRIVATE)
    private val key = "predictions_v2"

    @Synchronized fun update(product: String, mid: Double, hunt: LiquidityHuntModel.Result, actor: ActorStateModel.Result): Summary {
        val now = System.currentTimeMillis()
        val rows = load()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            if (r.optString("product") != product || r.optString("result") != "PENDING") continue
            val lowSeen = min(r.optDouble("lowSeen", mid), mid)
            val highSeen = max(r.optDouble("highSeen", mid), mid)
            r.put("lowSeen", lowSeen).put("highSeen", highSeen)
            if (mid <= r.optDouble("zoneHigh")) r.put("zoneTouched", true)
            val abort = r.optDouble("abort")
            val target = r.optDouble("target")
            when {
                abort > 0 && mid <= abort -> r.put("result", "FAILED").put("resolvedAt", now).put("note", "Abort level hit before bounce objective")
                r.optBoolean("zoneTouched") && target > 0 && mid >= target -> r.put("result", "HIT").put("resolvedAt", now).put("note", "Sweep zone touched, then bounce objective reached")
                now - r.optLong("createdAt") > 20 * 60_000L -> r.put("result", "EXPIRED").put("resolvedAt", now).put("note", if (r.optBoolean("zoneTouched")) "Zone touched but bounce objective not reached in 20m" else "Sweep zone not reached in 20m")
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
        return summarize(rows)
    }

    private fun summarize(rows: JSONArray): Summary {
        var resolved=0; var zoneHits=0; var wins=0; var fails=0; var pending=0
        var latestTime=0L; var latest="No resolved calls yet"
        for(i in 0 until rows.length()) {
            val r=rows.optJSONObject(i)?:continue
            when(r.optString("result")) {
                "PENDING" -> pending++
                else -> { resolved++; if(r.optBoolean("zoneTouched"))zoneHits++; if(r.optString("result")=="HIT")wins++ else fails++
                    val t=r.optLong("resolvedAt"); if(t>latestTime){ latestTime=t; latest="${r.optString("result")} • ${r.optString("product")} • ${r.optString("note")}" }
                }
            }
        }
        val zoneRate=if(resolved>0) ((zoneHits*100.0)/resolved).roundToInt() else 0
        val bounceRate=if(wins+fails>0) ((wins*100.0)/(wins+fails)).roundToInt() else 0
        return Summary(zoneRate,bounceRate,resolved,latest,pending)
    }

    private fun load(): JSONArray = try { JSONArray(prefs.getString(key,"[]") ?: "[]") } catch (_:Exception) { JSONArray() }
    private fun save(rows: JSONArray) { prefs.edit().putString(key, rows.toString()).apply() }
    private fun trim(rows: JSONArray) {
        if(rows.length()<=250)return
        val kept=JSONArray(); for(i in rows.length()-250 until rows.length()) kept.put(rows.get(i))
        prefs.edit().putString(key, kept.toString()).apply()
    }
}
