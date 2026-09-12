package com.cryptoai.overlay

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 54, 36, 54)
        }

        val title = TextView(this).apply {
            text = "Crypto AI — Coinbase Live Overlay"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }

        val pair = EditText(this).apply {
            hint = "Coinbase product, e.g. VVV-USD"
            setText("VVV-USD")
        }

        val accountTitle = TextView(this).apply {
            text = "\nOPTIONAL: CONNECT YOUR COINBASE POSITION"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }

        val accountNote = TextView(this).apply {
            text = "Use a CDP API key with VIEW permission only. Do not enable Trade or Transfer. The private key is kept in app memory for this session and is not saved to disk. This lets the overlay read your actual token balance, fills, commissions and fee tier."
            textSize = 14f
        }

        val keyName = EditText(this).apply {
            hint = "API key name: organizations/.../apiKeys/..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val privateKey = EditText(this).apply {
            hint = "Paste Coinbase EC private key (BEGIN EC PRIVATE KEY...)"
            minLines = 4
            maxLines = 8
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        val start = Button(this).apply { text = "START LIVE OVERLAY" }

        val note = TextView(this).apply {
            text = "Public market feed: Coinbase Level 2 + market trades. When a view-only key is supplied, account data is read from Coinbase Advanced Trade to calculate position accounting. The app does not place orders."
            textSize = 14f
        }

        box.addView(title)
        box.addView(pair)
        box.addView(accountTitle)
        box.addView(accountNote)
        box.addView(keyName)
        box.addView(privateKey)
        box.addView(start)
        box.addView(note)
        scroll.addView(box)
        setContentView(scroll)

        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Allow Display over other apps, then tap Start again.", Toast.LENGTH_LONG).show()
            } else {
                val product = pair.text.toString().uppercase().trim()
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
