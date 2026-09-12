package com.cryptoai.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(36,60,36,36) }
        val title = TextView(this).apply { text="Crypto AI — Coinbase Live Overlay"; textSize=24f }
        val pair = EditText(this).apply { hint="Coinbase product, e.g. MDT-USD"; setText("MDT-USD") }
        val start = Button(this).apply { text="START LIVE OVERLAY" }
        val note = TextView(this).apply { text="Advisory mode: reads Coinbase public Level 2 + market trades. It does not place orders or require Coinbase credentials."; textSize=15f }
        box.addView(title); box.addView(pair); box.addView(start); box.addView(note); setContentView(box)
        start.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this,"Allow Display over other apps, then tap Start again.",Toast.LENGTH_LONG).show()
            } else {
                val i=Intent(this,OverlayService::class.java).putExtra("product", pair.text.toString().uppercase().trim())
                startForegroundService(i)
            }
        }
    }
}
