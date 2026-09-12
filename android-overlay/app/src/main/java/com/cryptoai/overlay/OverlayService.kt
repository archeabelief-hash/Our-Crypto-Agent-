package com.cryptoai.overlay

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.TextView

class OverlayService:Service(){ private lateinit var wm:WindowManager; private lateinit var view:TextView; private var engine:MarketEngine?=null
 override fun onBind(i:Intent?):IBinder?=null
 override fun onStartCommand(i:Intent?,f:Int,id:Int):Int{ val product=i?.getStringExtra("product")?:"BTC-USD"; startForegroundNow(product); show(product); return START_NOT_STICKY }
 private fun startForegroundNow(p:String){ val cid="crypto_live"; val nm=getSystemService(NotificationManager::class.java); nm.createNotificationChannel(NotificationChannel(cid,"Crypto live analysis",NotificationManager.IMPORTANCE_LOW)); val n=Notification.Builder(this,cid).setContentTitle("Crypto AI Overlay").setContentText("Analyzing $p live").setSmallIcon(android.R.drawable.stat_notify_sync).build(); startForeground(7,n) }
 private fun show(p:String){ wm=getSystemService(WINDOW_SERVICE) as WindowManager; view=TextView(this).apply{ text="CONNECTING — $p"; textSize=14f; setTextColor(0xffffffff.toInt()); setBackgroundColor(0xdd101820.toInt()); setPadding(24,18,24,18) }
  val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=20;y=180}
  view.setOnTouchListener(object:View.OnTouchListener{var ix=0;var iy=0;var tx=0f;var ty=0f;override fun onTouch(v:View,e:android.view.MotionEvent):Boolean{when(e.action){0->{ix=lp.x;iy=lp.y;tx=e.rawX;ty=e.rawY};2->{lp.x=ix+(e.rawX-tx).toInt();lp.y=iy+(e.rawY-ty).toInt();wm.updateViewLayout(view,lp)}};return true}}); wm.addView(view,lp)
  engine=MarketEngine(p){s-> view.post{view.text="${s.product}   ${s.status}\n${s.signal}   ${s.confidence}%\nBid ${fmt(s.bid)}  Ask ${fmt(s.ask)}\nBook ${if(s.imbalance>=0)"BUY" else "SELL"} ${kotlin.math.abs(s.imbalance*100).toInt()}%\nSpoof-risk ${s.spoofRisk}/100\nModel target ${fmt(s.target)}\nInvalidation ${fmt(s.invalidation)}\n\nAdvisory only • drag me"} }}.also{it.start()}
 }
 private fun fmt(v:Double)=if(v==0.0)"—" else if(v<1)"%.6f".format(v) else "%.2f".format(v)
 override fun onDestroy(){engine?.stop();if(::view.isInitialized)wm.removeView(view);super.onDestroy()}
}
