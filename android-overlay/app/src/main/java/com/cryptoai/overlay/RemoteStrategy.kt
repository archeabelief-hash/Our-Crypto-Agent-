package com.cryptoai.overlay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RemoteStrategy {
    data class Config(
        val version: Int = 1,
        val depthPct: Double = 0.006,
        val imbalanceWeight: Double = 0.55,
        val flowWeight: Double = 0.45,
        val longThreshold: Double = 0.28,
        val shortThreshold: Double = -0.28,
        val maxSpoofRisk: Int = 75,
        val cancelWeight: Double = 55.0,
        val wallWeight: Double = 45.0,
        val wallScale: Double = 12.0,
        val confidenceCap: Int = 95,
        val rangeFloorPct: Double = 0.0015,
        val targetMultiple: Double = 4.0,
        val invalidationMultiple: Double = 3.0,
        val refreshSeconds: Long = 60
    )

    @Volatile private var config = Config()
    @Volatile private var lastFetchMs = 0L
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private const val URL = "https://raw.githubusercontent.com/archeabelief-hash/Our-Crypto-Agent-/main/remote/crypto-overlay-strategy.json"

    fun current(): Config {
        refreshIfNeeded()
        return config
    }

    private fun refreshIfNeeded() {
        val now = System.currentTimeMillis()
        val refreshMs = config.refreshSeconds.coerceAtLeast(15) * 1000L
        if (now - lastFetchMs < refreshMs) return
        synchronized(this) {
            if (now - lastFetchMs < refreshMs) return
            lastFetchMs = now
            Thread {
                try {
                    val response = client.newCall(Request.Builder().url(URL).cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()).execute()
                    response.use {
                        if (!it.isSuccessful) return@use
                        val body = it.body?.string() ?: return@use
                        val j = JSONObject(body)
                        config = Config(
                            version = j.optInt("version", config.version),
                            depthPct = j.optDouble("depthPct", config.depthPct),
                            imbalanceWeight = j.optDouble("imbalanceWeight", config.imbalanceWeight),
                            flowWeight = j.optDouble("flowWeight", config.flowWeight),
                            longThreshold = j.optDouble("longThreshold", config.longThreshold),
                            shortThreshold = j.optDouble("shortThreshold", config.shortThreshold),
                            maxSpoofRisk = j.optInt("maxSpoofRisk", config.maxSpoofRisk),
                            cancelWeight = j.optDouble("cancelWeight", config.cancelWeight),
                            wallWeight = j.optDouble("wallWeight", config.wallWeight),
                            wallScale = j.optDouble("wallScale", config.wallScale),
                            confidenceCap = j.optInt("confidenceCap", config.confidenceCap),
                            rangeFloorPct = j.optDouble("rangeFloorPct", config.rangeFloorPct),
                            targetMultiple = j.optDouble("targetMultiple", config.targetMultiple),
                            invalidationMultiple = j.optDouble("invalidationMultiple", config.invalidationMultiple),
                            refreshSeconds = j.optLong("refreshSeconds", config.refreshSeconds)
                        )
                    }
                } catch (_: Exception) {
                    // Keep the last known-good config if the remote file is unavailable.
                }
            }.start()
        }
    }
}
