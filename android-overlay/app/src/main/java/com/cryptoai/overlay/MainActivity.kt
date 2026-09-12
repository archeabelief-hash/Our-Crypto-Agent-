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
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 36) }
        fun label(text: String, size: Float = 14f, bold: Boolean = false) = TextView(this).apply { this.text=text; textSize=size; if(bold)setTypeface(typeface,Typeface.BOLD) }

        val title=label("Twisted Psyche Crypto",24f,true)
        val sub=label("Mobile v0.8 • same current market logic as the PC build: fresh Coinbase USD scan, 1m / 5m / 15m windows, 30-day regime context, and dollar-size-aware liquidity/slippage.")
        val scanTitle=label("\nFRESH MARKET-WIDE SCAN",18f,true)
        val scanStatus=label("Starting fresh scan…",17f,true)
        val scanProgress=label("Loading live pairs…",13f)

        val amountTitle=label("\nTRADE AMOUNT USED BY THE SCANNER",16f,true)
        val amountSpinner=Spinner(this)
        val amountChoices=listOf("$50","$100","$150","Custom amount")
        amountSpinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,amountChoices)
        amountSpinner.setSelection(1)
        val customAmount=EditText(this).apply { hint="Custom dollars, e.g. 1000";inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;visibility=View.GONE }

        val best1=label("Best 1m: scanning…",15f)
        val best5=label("Best 5m: scanning…",15f)
        val best15=label("Best 15m: scanning…",15f)
        val suggested=label("Suggested: scanning…",17f,true)
        val topList=label("",13f)
        val rescan=Button(this).apply{text="RESCAN ALL LIVE PAIRS FOR THIS AMOUNT"}
        val useSuggested=Button(this).apply{text="USE BEST SUGGESTED PAIR";isEnabled=false}
        val pair=EditText(this).apply{hint="Token pair, e.g. VVV-USD or BTC-USD";setText("BTC-USD");textSize=18f}
        val feeNote=label("Profit estimates use the selected dollar amount, visible ask depth, weighted fill price, spread/slippage and a conservative 0.60% fee each side. A larger amount does not turn a negative percentage edge positive and may worsen slippage.",13f)
        val start=Button(this).apply{text="START / REFRESH FLOATING FORECAST"}
        val stop=Button(this).apply{text="STOP FORECAST + CLOSE OVERLAY"}
        val exit=Button(this).apply{text="EXIT APP"}
        val splitHint=label("Split-screen: start the forecast, then Android Recents → app icon → Split screen. Put Coinbase in the other pane. The floating overlay remains draggable.",13f)
        val accountToggle=Button(this).apply{text="OPTIONAL: CONNECT MY COINBASE READ-ONLY DATA"}
        val accountBox=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE}
        val accountNote=label("No keys are needed for public scanning. These fields are only for your private Coinbase balances/fills/actual fee data. Use VIEW-ONLY access.")
        val keyName=EditText(this).apply{hint="Coinbase API key name";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}
        val privateKey=EditText(this).apply{hint="Coinbase EC private key";minLines=4;maxLines=8;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS}
        accountBox.addView(accountNote);accountBox.addView(keyName);accountBox.addView(privateKey)
        val explanation=label("The phone now evaluates the same three trade windows as PC. It only marks a setup clean when estimated net profit is positive after costs and its score/risk-reward clear the current thresholds. Estimates are not guarantees.")

        listOf<View>(title,sub,scanTitle,scanStatus,scanProgress,amountTitle,amountSpinner,customAmount,best1,best5,best15,suggested,topList,rescan,useSuggested,pair,feeNote,start,stop,exit,splitHint,accountToggle,accountBox,explanation).forEach{box.addView(it)}
        scroll.addView(box);setContentView(scroll)

        fun selectedBankroll():Double=when(amountSpinner.selectedItemPosition){0->50.0;1->100.0;2->150.0;else->customAmount.text.toString().toDoubleOrNull()?.coerceAtLeast(1.0)?:100.0}
        fun amountText(v:Double)=if(v%1.0==0.0)String.format(Locale.US,"%.0f",v)else String.format(Locale.US,"%.2f",v)

        fun launchOverlay(){
            if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));Toast.makeText(this,"Allow Display over other apps, then tap Start again.",Toast.LENGTH_LONG).show();return}
            val product=pair.text.toString().uppercase(Locale.US).trim().ifBlank{"BTC-USD"}
            val i=Intent(this,OverlayService::class.java).putExtra("product",product).putExtra("bankroll",selectedBankroll()).putExtra("api_key_name",keyName.text.toString().trim()).putExtra("api_private_key",privateKey.text.toString().trim())
            stopService(Intent(this,OverlayService::class.java));startForegroundService(i);privateKey.text.clear()
        }

        fun planLine(prefix:String,p:AllPairsScanner.Pick?,mins:Int):String{
            val hp=p?.plans?.get(mins)?:return "$prefix: no candidate"
            val state=if(hp.valid&&hp.net>0&&hp.score>=55&&hp.rr>=.85)"PASS" else "FAIL"
            return "$prefix: ${p.product} • $state • score ${hp.score}/100 • est. net ${signed(hp.net)} • target ${fmt(hp.target)} • entry slip ${String.format(Locale.US,"%.3f",hp.slip*100)}%"
        }

        fun showScan(r:AllPairsScanner.Result){
            lastScan=r
            scanStatus.text=if(r.suggestedProduct==null)"NO CLEAN PROFIT SETUP FOR $${amountText(r.amount)} RIGHT NOW" else "BEST RIGHT NOW: ${r.suggestedProduct} • ${r.suggestedWindow}m"
            scanProgress.text="Fresh scan: ${r.scannedPairs} live USD pairs • ${r.detailedPairs} finalists deep-checked • amount $${amountText(r.amount)}"
            best1.text=planLine("Best 1m",r.best1,1);best5.text=planLine("Best 5m",r.best5,5);best15.text=planLine("Best 15m",r.best15,15)
            suggested.text=if(r.suggestedProduct==null)"Suggested: SKIP FOR NOW" else "Suggested: $${amountText(r.amount)} on ${r.suggestedProduct} • ${r.suggestedWindow}m window"
            topList.text=if(r.top.isEmpty())r.note else buildString{append("\nTOP CURRENT CANDIDATES\n");r.top.forEachIndexed{idx,p->append("${idx+1}. ${p.product} • price ${fmt(p.price)} • 1m ${p.plans[1]?.score?:0}/100 ${signed(p.plans[1]?.net?:0.0)} • 5m ${p.plans[5]?.score?:0}/100 ${signed(p.plans[5]?.net?:0.0)} • 15m ${p.plans[15]?.score?:0}/100 ${signed(p.plans[15]?.net?:0.0)} • RSI ${String.format(Locale.US,"%.1f",p.rsi)} • 30d ${String.format(Locale.US,"%+.1f%%",p.history30d*100)}\n")};append("\n${r.note}")}
            useSuggested.isEnabled=r.suggestedProduct!=null
        }

        fun runScan(){
            scanner?.stop();scanStatus.text="SCANNING THE MARKET NOW…";scanProgress.text="Starting fresh live scan…";best1.text="Best 1m: scanning…";best5.text="Best 5m: scanning…";best15.text="Best 15m: scanning…";suggested.text="Suggested: scanning…";topList.text="";useSuggested.isEnabled=false
            val bank=selectedBankroll();scanner=AllPairsScanner().also{s->s.scan(bank,progress={msg->runOnUiThread{scanProgress.text=msg}},done={result->runOnUiThread{showScan(result)}})}
        }

        amountSpinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){customAmount.visibility=if(position==3)View.VISIBLE else View.GONE;if(position!=3&&lastScan!=null)runScan()};override fun onNothingSelected(parent:AdapterView<*>?){}}
        customAmount.setOnEditorActionListener{_,_,_->runScan();false}
        rescan.setOnClickListener{runScan()}
        useSuggested.setOnClickListener{val r=lastScan?:return@setOnClickListener;val p=r.suggestedProduct?:return@setOnClickListener;pair.setText(p);launchOverlay()}
        accountToggle.setOnClickListener{accountBox.visibility=if(accountBox.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        start.setOnClickListener{launchOverlay()};stop.setOnClickListener{stopService(Intent(this,OverlayService::class.java));Toast.makeText(this,"Live forecast stopped.",Toast.LENGTH_SHORT).show()};exit.setOnClickListener{scanner?.stop();stopService(Intent(this,OverlayService::class.java));finishAndRemoveTask()}
        runScan()
    }

    private fun signed(v:Double):String=if(v>=0)"+$${String.format(Locale.US,"%.2f",v)}" else "-$${String.format(Locale.US,"%.2f",abs(v))}"
    private fun fmt(v:Double):String=when{!v.isFinite()||v<=0->"—";v>=1000->String.format(Locale.US,"%.2f",v);v>=1->String.format(Locale.US,"%.4f",v);v>=.01->String.format(Locale.US,"%.6f",v);v>=.0001->String.format(Locale.US,"%.8f",v);v>=.000001->String.format(Locale.US,"%.10f",v);else->String.format(Locale.US,"%.12f",v)}
    override fun onDestroy(){scanner?.stop();super.onDestroy()}
}
