package com.cryptoai.overlay

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var view: TextView
    private var engine: MarketEngine? = null
    private var accountClient: CoinbaseAccountClient? = null
    @Volatile private var running = false
    @Volatile private var latestMarket: MarketEngine.Snapshot? = null
    @Volatile private var latestPosition: CoinbaseAccountClient.Position? = null
    @Volatile private var accountStatus = "Your account: not connected"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val product = intent?.getStringExtra("product") ?: "BTC-USD"
        val keyName = intent?.getStringExtra("api_key_name").orEmpty()
        val privateKey = intent?.getStringExtra("api_private_key").orEmpty()
        startForegroundNow(product)
        show(product)
        if (keyName.isNotBlank() && privateKey.isNotBlank()) {
            accountClient = CoinbaseAccountClient(keyName, privateKey)
            startAccountSync(product)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNow(product: String) {
        val channelId = "crypto_live"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(channelId, "Live market signals", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Twisted Psyche Crypto")
            .setContentText("Watching $product live")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()
        startForeground(7, notification)
    }

    private fun show(product: String) {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        view = TextView(this).apply {
            text = "CONNECTING — $product"
            textSize = 13f
            setTextColor(0xffffffff.toInt())
            setBackgroundColor(0xee101820.toInt())
            setPadding(22, 16, 22, 16)
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 180 }

        view.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, event: android.view.MotionEvent): Boolean {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { initialX = lp.x; initialY = lp.y; touchX = event.rawX; touchY = event.rawY }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        lp.x = initialX + (event.rawX - touchX).toInt()
                        lp.y = initialY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(view, lp)
                    }
                }
                return true
            }
        })
        wm.addView(view, lp)

        engine = MarketEngine(product) { snapshot -> latestMarket = snapshot; render() }.also { it.start() }
    }

    private fun startAccountSync(product: String) {
        running = true
        thread(name = "coinbase-account-sync", isDaemon = true) {
            while (running) {
                try {
                    accountStatus = "Your account: syncing…"; render()
                    latestPosition = accountClient?.loadPosition(product)
                    accountStatus = "Your account: connected read-only"
                } catch (e: Exception) {
                    accountStatus = "Account connection error: ${e.message?.take(80) ?: "unknown"}"
                }
                render()
                try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun render() {
        if (!::view.isInitialized) return
        val market = latestMarket
        val position = latestPosition
        view.post {
            val sb = StringBuilder()
            if (market == null) {
                sb.append("CONNECTING TO LIVE MARKETS…\n")
            } else {
                val livePrice = (market.bid + market.ask) / 2.0
                sb.append("${market.product}   ${market.status}\n")
                sb.append("━━━━━━━━━━━━━━━━\n")
                sb.append("▶ ${market.signal}\n")
                sb.append("Confidence: ${market.confidence}%\n")
                sb.append("Live price: ${fmt(livePrice)}\n")
                sb.append("Buy around: ${fmt(market.buyLow)} – ${fmt(market.buyHigh)}\n")
                sb.append("Take profit near: ${fmt(market.target)}\n")
                sb.append("Get out below: ${fmt(market.invalidation)}\n")
                sb.append("Why: ${market.reason}\n")
                sb.append("Market feeds: ${market.sourcesLive}/${market.sourcesTotal} live\n")
                sb.append("Book pressure: ${if (market.imbalance >= 0) "BUYERS" else "SELLERS"} ${abs(market.imbalance * 100).toInt()}%\n")
                sb.append("Book risk: ${riskWord(market.spoofRisk)} (${market.spoofRisk}/100)\n")
            }

            sb.append("────────────\n")
            sb.append(accountStatus).append('\n')

            if (position != null) {
                val mid = market?.let { (it.bid + it.ask) / 2.0 } ?: 0.0
                val exitRate = position.takerFeeRate.coerceIn(0.0, 0.25)
                val breakEven = if (position.balance > 0 && position.costBasis > 0) position.costBasis / (position.balance * (1.0 - exitRate).coerceAtLeast(0.0001)) else 0.0
                val currentNet = if (mid > 0) mid * position.balance * (1.0 - exitRate) else 0.0
                val pnl = if (position.costBasis > 0 && mid > 0) currentNet - position.costBasis else 0.0

                sb.append("You own: ${qty(position.balance)} ${position.token}\n")
                sb.append("Your average buy: ${fmt(position.avgEntry)}\n")
                sb.append("Money put in: $${money(position.costBasis)}\n")
                sb.append("Fees already paid: $${money(position.feesPaid)}\n")
                if (breakEven > 0) sb.append("Break even after est. sell fee: ${fmt(breakEven)}\n")
                if (position.costBasis > 0 && mid > 0) sb.append("If sold now (est.): ${if (pnl >= 0) "+" else "-"}$${money(abs(pnl))}\n")
                if (position.recent.isNotEmpty()) {
                    sb.append("Last trades:\n")
                    position.recent.take(4).forEach { fill ->
                        val side = if (fill.side.equals("BUY", true)) "Bought" else "Sold"
                        sb.append("$side ${qty(fill.size)} @ ${fmt(fill.price)}  fee $${money(fill.commission)}\n")
                    }
                }
            }

            sb.append("\nSignal = live algorithm, not a guarantee • drag me")
            view.text = sb.toString()
        }
    }

    private fun riskWord(v: Int): String = when { v >= 70 -> "HIGH"; v >= 45 -> "MEDIUM"; else -> "LOW" }
    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun qty(v: Double): String = if (v < 1.0) String.format(Locale.US, "%.6f", v) else String.format(Locale.US, "%.4f", v)
    private fun fmt(v: Double): String = when { v == 0.0 -> "—"; v < 1 -> String.format(Locale.US, "%.6f", v); else -> String.format(Locale.US, "%.2f", v) }

    override fun onDestroy() {
        running = false
        engine?.stop()
        if (::view.isInitialized) wm.removeView(view)
        super.onDestroy()
    }
}
