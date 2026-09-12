package com.cryptoai.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var view: TextView
    private var engine: MarketEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val product = intent?.getStringExtra("product") ?: "BTC-USD"
        startForegroundNow(product)
        show(product)
        return START_NOT_STICKY
    }

    private fun startForegroundNow(product: String) {
        val channelId = "crypto_live"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                channelId,
                "Crypto live analysis",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Crypto AI Overlay")
            .setContentText("Analyzing $product live")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        startForeground(7, notification)
    }

    private fun show(product: String) {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        view = TextView(this).apply {
            text = "CONNECTING — $product"
            textSize = 14f
            setTextColor(0xffffffff.toInt())
            setBackgroundColor(0xdd101820.toInt())
            setPadding(24, 18, 24, 18)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 180
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(view, params)
                    }
                }
                return true
            }
        })

        wm.addView(view, params)

        engine = MarketEngine(product) { snapshot ->
            view.post {
                val side = if (snapshot.imbalance >= 0) "BUY" else "SELL"
                val bookPct = abs(snapshot.imbalance * 100).toInt()
                view.text = buildString {
                    append("${snapshot.product}   ${snapshot.status}\n")
                    append("${snapshot.signal}   ${snapshot.confidence}%\n")
                    append("Bid ${fmt(snapshot.bid)}  Ask ${fmt(snapshot.ask)}\n")
                    append("Book $side $bookPct%\n")
                    append("Spoof-risk ${snapshot.spoofRisk}/100\n")
                    append("Model target ${fmt(snapshot.target)}\n")
                    append("Invalidation ${fmt(snapshot.invalidation)}\n\n")
                    append("Advisory only • drag me")
                }
            }
        }.also { it.start() }
    }

    private fun fmt(value: Double): String = when {
        value == 0.0 -> "—"
        value < 1 -> "%.6f".format(value)
        else -> "%.2f".format(value)
    }

    override fun onDestroy() {
        engine?.stop()
        if (::view.isInitialized) {
            wm.removeView(view)
        }
        super.onDestroy()
    }
}
