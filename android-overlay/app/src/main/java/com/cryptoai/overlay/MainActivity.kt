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
            text = "Built for split-screen with Coinbase. Pick how much money you want to trade, then the overlay calculates tokens, fees, expected profit and planned loss live."
            textSize = 14f
        }

        val pair = EditText(this).apply {
            hint = "Token pair, e.g. VVV-USD or BTC-USD"
            setText("VVV-USD")
            textSize = 18f
        }

        val amountTitle = TextView(this).apply {
            text = "\nHOW MUCH DO YOU WANT TO TRADE?"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }

        val amountSpinner = Spinner(this)
        val amountChoices = listOf("$50", "$100", "Custom amount")
        amountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, amountChoices)
        amountSpinner.setSelection(1)

        val customAmount = EditText(this).apply {
            hint = "Custom dollars, e.g. 75"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            visibility = View.GONE
        }

        amountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                customAmount.visibility = if (position == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val feeNote = TextView(this).apply {
            text = "Public-data mode assumes 0.60% fee to buy + 0.60% fee to sell so profit is not overstated."
            textSize = 13f
        }

        val start = Button(this).apply { text = "START / REFRESH LIVE SIGNALS" }
        val stop = Button(this).apply { text = "STOP SIGNALS + CLOSE OVERLAY" }
        val exit = Button(this).apply { text = "EXIT APP" }

        val splitHint = TextView(this).apply {
            text = "Split-screen: start the signal, then Android Recents → tap the app icon → Split screen. Put Coinbase in the other pane."
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
            text = "The overlay tells you: BUY NOW / WAIT / SELL NOW, how much to spend, estimated tokens, buy price, profit target, estimated profit after fees, planned loss at the stop, and whether the setup is worth taking."
            textSize = 14f
        }

        box.addView(title)
        box.addView(sub)
        box.addView(pair)
        box.addView(amountTitle)
        box.addView(amountSpinner)
        box.addView(customAmount)
        box.addView(feeNote)
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
                val bankroll = when (amountSpinner.selectedItemPosition) {
                    0 -> 50.0
                    1 -> 100.0
                    else -> customAmount.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 100.0
                }
                val i = Intent(this, OverlayService::class.java)
                    .putExtra("product", product)
                    .putExtra("bankroll", bankroll)
                    .putExtra("api_key_name", keyName.text.toString().trim())
                    .putExtra("api_private_key", privateKey.text.toString().trim())
                stopService(Intent(this, OverlayService::class.java))
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
