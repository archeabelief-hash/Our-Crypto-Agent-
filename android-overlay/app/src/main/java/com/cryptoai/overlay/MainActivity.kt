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
            setPadding(36, 48, 36, 48)
        }

        val title = TextView(this).apply {
            text = "Twisted Psyche Crypto"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "Live buy / wait / sell signals from public market feeds. Coinbase Level 2 is the main order book; Kraken, OKX and Binance are used as extra live confirmation when that token is listed there."
            textSize = 15f
        }
        val pair = EditText(this).apply {
            hint = "Token pair, e.g. VVV-USD or BTC-USD"
            setText("VVV-USD")
            textSize = 18f
        }
        val start = Button(this).apply { text = "START LIVE SIGNALS" }

        val accountToggle = Button(this).apply { text = "OPTIONAL: CONNECT MY COINBASE READ-ONLY DATA" }
        val accountBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val accountNote = TextView(this).apply {
            text = "You do NOT need keys for live public order books. These fields are only for your private Coinbase balance, fills, purchase prices and fees. Use a VIEW-ONLY Coinbase CDP key. Leave them blank for public-data mode."
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
            text = "What you will see:\nBUY NOW = the live algorithm currently sees a strong buy setup.\nGET READY TO BUY = conditions are improving but not strong enough yet.\nWAIT = no clean edge.\nGET READY TO SELL / SELL NOW = live pressure has turned against the trade.\n\nThe app also shows a buy zone, profit target, exit-below price, confidence and the reason for the signal."
            textSize = 14f
        }

        box.addView(title)
        box.addView(sub)
        box.addView(pair)
        box.addView(start)
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
    }
}
