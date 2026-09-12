package com.cryptoai.overlay

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 36)
        }

        val title = TextView(this).apply {
            text = "Twisted Psyche Crypto"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Built for split-screen with Coinbase. Run the app in one pane and Coinbase in the other while the floating signal card stays live."
            textSize = 14f
        }
        val pair = EditText(this).apply {
            hint = "Token pair, e.g. VVV-USD or BTC-USD"
            setText("VVV-USD")
            textSize = 18f
        }
        val start = Button(this).apply { text = "START / REFRESH LIVE SIGNALS" }
        val stop = Button(this).apply { text = "STOP SIGNALS + CLOSE OVERLAY" }
        val exit = Button(this).apply { text = "EXIT APP" }

        val splitHint = TextView(this).apply {
            text = "Split-screen tip: start the signal, then use Android Recents → tap the app icon → Split screen. Put Coinbase in the other pane. The overlay can still float above both panes."
            textSize = 13f
        }

        val accountToggle = Button(this).apply { text = "OPTIONAL: CONNECT MY COINBASE READ-ONLY DATA" }
        val accountBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val accountNote = TextView(this).apply {
            text = "No keys are needed for public market feeds. These fields are only for private Coinbase balances, fills, entry prices and fees. Use VIEW-ONLY access."
            textSize = 14f
        }
        val keyName = EditText(this).apply {
            hint = "Coinbase API key name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val privateKey = EditText(this).apply {
            hint = "Coinbase EC private key"
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        accountBox.addView(accountNote)
        accountBox.addView(keyName)
        accountBox.addView(privateKey)

        val explanation = TextView(this).apply {
            text = "BUY NOW = strongest live buy condition.\nGET READY TO BUY = improving, wait for confirmation.\nWAIT = no clean edge.\nGET READY TO SELL / SELL NOW = pressure has turned against the trade.\n\nThe overlay shows live price, buy zone, take-profit area, exit-below price, confidence and reason."
            textSize = 14f
        }

        box.addView(title)
        box.addView(sub)
        box.addView(pair)
        box.addView(start)
        box.addView(stop)
        box.addView(exit)
        box.addView(splitHint)
        box.addView(accountToggle)
        box.addView(accountBox)
        box.addView(explanation)
        scroll.addView(box)
        setContentView(scroll)

        accountToggle.setOnClickListener {
            accountBox.visibility = if (accountBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Allow Display over other apps, then tap Start again.", Toast.LENGTH_LONG).show()
            } else {
                val product = pair.text.toString().uppercase().trim().ifBlank { "BTC-USD" }
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("product", product)
                    .putExtra("api_key_name", keyName.text.toString().trim())
                    .putExtra("api_private_key", privateKey.text.toString().trim())
                startForegroundService(i)
                privateKey.text.clear()
            }
        }

        stop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Live signals stopped.", Toast.LENGTH_SHORT).show()
        }

        exit.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            finishAndRemoveTask()
        }
    }
}
