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
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private var scanner: AllPairsScanner? = null
    private var lastScan: AllPairsScanner.Result? = null

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
            text = "Mobile Forecast Mode • on every launch it fresh-scans live Coinbase USD pairs, ranks the best $50 and $100 opportunities, then lets you load the winner into the floating live forecast."
            textSize = 14f
        }

        val scanTitle = TextView(this).apply {
            text = "\nFRESH MARKET-WIDE SCAN"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val scanStatus = TextView(this).apply {
            text = "Starting fresh scan…"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        val scanProgress = TextView(this).apply { text = "Loading live pairs…"; textSize = 13f }
        val best50 = TextView(this).apply { text = "Best for $50: scanning…"; textSize = 15f }
        val best100 = TextView(this).apply { text = "Best for $100: scanning…"; textSize = 15f }
        val suggested = TextView(this).apply { text = "Suggested: scanning…"; textSize = 17f; setTypeface(typeface, Typeface.BOLD) }
        val topList = TextView(this).apply { text = ""; textSize = 13f }
        val rescan = Button(this).apply { text = "RESCAN ALL LIVE PAIRS NOW" }
        val useSuggested = Button(this).apply { text = "USE BEST SUGGESTED PAIR"; isEnabled = false }

        val pair = EditText(this).apply {
            hint = "Token pair, e.g. VVV-USD or BTC-USD"
            setText("BTC-USD")
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
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { customAmount.visibility = if (position == 2) View.VISIBLE else View.GONE }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val feeNote = TextView(this).apply {
            text = "Ranking uses conservative public-mode costs: 0.60% estimated fee each side plus live spread/order-book fill effects. It can say SKIP if nothing currently looks worth the risk."
            textSize = 13f
        }

        val start = Button(this).apply { text = "START / REFRESH FLOATING FORECAST" }
        val stop = Button(this).apply { text = "STOP FORECAST + CLOSE OVERLAY" }
        val exit = Button(this).apply { text = "EXIT APP" }

        val splitHint = TextView(this).apply {
            text = "Split-screen: start the forecast, then Android Recents → tap the app icon → Split screen. Put Coinbase in the other pane. The floating overlay stays draggable."
            textSize = 13f
        }

        val accountToggle = Button(this).apply { text = "OPTIONAL: CONNECT MY COINBASE READ-ONLY DATA" }
        val accountBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val accountNote = TextView(this).apply {
            text = "No keys are needed for the scanner or public forecast. These fields are only for your private Coinbase balances/fills/actual fee data. Use VIEW-ONLY access."
            textSize = 14f
        }
        val keyName = EditText(this).apply { hint = "Coinbase API key name"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        val privateKey = EditText(this).apply { hint = "Coinbase EC private key"; minLines = 4; maxLines = 8; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
        accountBox.addView(accountNote); accountBox.addView(keyName); accountBox.addView(privateKey)

        val explanation = TextView(this).apply {
            text = "Flow: 1) app scans the market, 2) compare BEST $50 and BEST $100, 3) use the suggested pair, 4) floating forecast watches that pair live for likely direction, shelves, target, stop, fees and risk. Scanner scores are estimates, not guaranteed accuracy."
            textSize = 14f
        }

        listOf<View>(title, sub, scanTitle, scanStatus, scanProgress, best50, best100, suggested, topList, rescan, useSuggested, pair, amountTitle, amountSpinner, customAmount, feeNote, start, stop, exit, splitHint, accountToggle, accountBox, explanation).forEach { box.addView(it) }
        scroll.addView(box)
        setContentView(scroll)

        fun selectedBankroll(): Double = when (amountSpinner.selectedItemPosition) {
            0 -> 50.0
            1 -> 100.0
            else -> customAmount.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0) ?: 100.0
        }

        fun launchOverlay() {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this, "Allow Display over other apps, then tap Start again.", Toast.LENGTH_LONG).show()
                return
            }
            val product = pair.text.toString().uppercase(Locale.US).trim().ifBlank { "BTC-USD" }
            val i = Intent(this, OverlayService::class.java)
                .putExtra("product", product)
                .putExtra("bankroll", selectedBankroll())
                .putExtra("api_key_name", keyName.text.toString().trim())
                .putExtra("api_private_key", privateKey.text.toString().trim())
            stopService(Intent(this, OverlayService::class.java))
            startForegroundService(i)
            privateKey.text.clear()
        }

        fun showScan(r: AllPairsScanner.Result) {
            lastScan = r
            scanStatus.text = if (r.suggestedProduct == null) "NO CLEAN TRADE RIGHT NOW" else "BEST RIGHT NOW: ${r.suggestedProduct}"
            scanProgress.text = "Fresh scan: ${r.scannedPairs} live USD pairs • ${r.detailedPairs} finalists deep-checked"
            best50.text = r.best50?.let { "Best for $50: ${it.product} • score ${it.score50}/100 • est. net ${signed(it.net50)} • target ${fmt(it.target)}" } ?: "Best for $50: no candidate"
            best100.text = r.best100?.let { "Best for $100: ${it.product} • score ${it.score100}/100 • est. net ${signed(it.net100)} • target ${fmt(it.target)}" } ?: "Best for $100: no candidate"
            suggested.text = if (r.suggestedAmount == null) "Suggested: SKIP FOR NOW" else "Suggested: $${r.suggestedAmount} on ${r.suggestedProduct}"
            topList.text = if (r.top.isEmpty()) r.note else buildString {
                append("\nTOP CURRENT CANDIDATES\n")
                r.top.forEachIndexed { index, p ->
                    append("${index + 1}. ${p.product} • price ${fmt(p.price)} • target ${fmt(p.target)} • $50 ${p.score50}/100 ${signed(p.net50)} • $100 ${p.score100}/100 ${signed(p.net100)}\n")
                }
                append("\n${r.note}")
            }
            useSuggested.isEnabled = r.suggestedProduct != null
        }

        fun runScan() {
            scanner?.stop()
            scanStatus.text = "SCANNING THE MARKET NOW…"
            scanProgress.text = "Starting fresh live scan…"
            best50.text = "Best for $50: scanning…"; best100.text = "Best for $100: scanning…"; suggested.text = "Suggested: scanning…"; topList.text = ""
            useSuggested.isEnabled = false
            scanner = AllPairsScanner().also { s ->
                s.scan(progress = { msg -> runOnUiThread { scanProgress.text = msg } }, done = { result -> runOnUiThread { showScan(result) } })
            }
        }

        rescan.setOnClickListener { runScan() }
        useSuggested.setOnClickListener {
            val r = lastScan ?: return@setOnClickListener
            val p = r.suggestedProduct ?: return@setOnClickListener
            pair.setText(p)
            when (r.suggestedAmount) { 50 -> amountSpinner.setSelection(0); 100 -> amountSpinner.setSelection(1) }
            launchOverlay()
        }
        accountToggle.setOnClickListener { accountBox.visibility = if (accountBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        start.setOnClickListener { launchOverlay() }
        stop.setOnClickListener { stopService(Intent(this, OverlayService::class.java)); Toast.makeText(this, "Live forecast stopped.", Toast.LENGTH_SHORT).show() }
        exit.setOnClickListener { scanner?.stop(); stopService(Intent(this, OverlayService::class.java)); finishAndRemoveTask() }

        runScan()
    }

    private fun signed(v: Double): String = if (v >= 0) "+$${String.format(Locale.US, "%.2f", v)}" else "-$${String.format(Locale.US, "%.2f", abs(v))}"
    private fun fmt(v: Double): String = when {
        !v.isFinite() || v <= 0 -> "—"
        v >= 1000 -> String.format(Locale.US, "%.2f", v)
        v >= 1 -> String.format(Locale.US, "%.4f", v)
        v >= .01 -> String.format(Locale.US, "%.6f", v)
        v >= .0001 -> String.format(Locale.US, "%.8f", v)
        v >= .000001 -> String.format(Locale.US, "%.10f", v)
        else -> String.format(Locale.US, "%.12f", v)
    }

    override fun onDestroy() { scanner?.stop(); super.onDestroy() }
}
