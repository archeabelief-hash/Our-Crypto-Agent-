package com.cryptoai.overlay

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object RemoteStrategy {
    data class Config(
        val version: Int = 2,
        val depthPct: Double = 0.006,
        val imbalanceWeight: Double = 0.30,
        val flowWeight: Double = 0.25,
        val momentumWeight: Double = 0.20,
        val venueWeight: Double = 0.15,
        val microPriceWeight: Double = 0.10,
        val buyNowThreshold: Double = 0.42,
        val sellNowThreshold: Double = -0.42,
        val watchThreshold: Double = 0.24,
        val maxSpoofRisk: Int = 70,
        val cancelWeight: Double = 55.0,
        val wallWeight: Double = 45.0,
        val wallScale: Double = 12.0,
        val confidenceCap: Int = 95,
        val rangeFloorPct: Double = 0.0015,
        val targetMultiple: Double = 4.0,
        val invalidationMultiple: Double = 2.5,
        val maxSpreadPct: Double = 0.004,
        val momentumWindowSeconds: Long = 10,
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
                    client.newCall(Request.Builder().url(URL).cacheControl(okhttp3.CacheControl.FORCE_NETWORK).build()).execute().use {
                        if (!it.isSuccessful) return@use
                        val j = JSONObject(it.body?.string() ?: return@use)
                        config = Config(
                            version = j.optInt("version", config.version),
                            depthPct = j.optDouble("depthPct", config.depthPct),
                            imbalanceWeight = j.optDouble("imbalanceWeight", config.imbalanceWeight),
                            flowWeight = j.optDouble("flowWeight", config.flowWeight),
                            momentumWeight = j.optDouble("momentumWeight", config.momentumWeight),
                            venueWeight = j.optDouble("venueWeight", config.venueWeight),
                            microPriceWeight = j.optDouble("microPriceWeight", config.microPriceWeight),
                            buyNowThreshold = j.optDouble("buyNowThreshold", config.buyNowThreshold),
                            sellNowThreshold = j.optDouble("sellNowThreshold", config.sellNowThreshold),
                            watchThreshold = j.optDouble("watchThreshold", config.watchThreshold),
                            maxSpoofRisk = j.optInt("maxSpoofRisk", config.maxSpoofRisk),
                            cancelWeight = j.optDouble("cancelWeight", config.cancelWeight),
                            wallWeight = j.optDouble("wallWeight", config.wallWeight),
                            wallScale = j.optDouble("wallScale", config.wallScale),
                            confidenceCap = j.optInt("confidenceCap", config.confidenceCap),
                            rangeFloorPct = j.optDouble("rangeFloorPct", config.rangeFloorPct),
                            targetMultiple = j.optDouble("targetMultiple", config.targetMultiple),
                            invalidationMultiple = j.optDouble("invalidationMultiple", config.invalidationMultiple),
                            maxSpreadPct = j.optDouble("maxSpreadPct", config.maxSpreadPct),
                            momentumWindowSeconds = j.optLong("momentumWindowSeconds", config.momentumWindowSeconds),
                            refreshSeconds = j.optLong("refreshSeconds", config.refreshSeconds)
                        )
                    }
                } catch (_: Exception) {
                    // Keep last known-good strategy if GitHub is temporarily unavailable.
                }
            }.start()
        }
    }
}
