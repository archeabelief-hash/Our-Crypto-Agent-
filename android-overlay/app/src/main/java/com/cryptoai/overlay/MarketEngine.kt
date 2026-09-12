package com.cryptoai.overlay

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.*

class MarketEngine(private val product:String, private val update:(Snapshot)->Unit) {
 data class Snapshot(val product:String,val bid:Double,val ask:Double,val imbalance:Double,val spoofRisk:Int,val signal:String,val confidence:Int,val target:Double,val invalidation:Double,val status:String)
 private val bids=sortedMapOf<Double,Double>(compareByDescending{it}); private val asks=sortedMapOf<Double,Double>()
 private val prior=mutableMapOf<String,Pair<Double,Long>>(); private var cancels=0; private var changes=0; private var buyFlow=0.0; private var sellFlow=0.0
 private val client=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build(); private var ws:WebSocket?=null
 fun start(){ val req=Request.Builder().url("wss://advanced-trade-ws.coinbase.com").build(); ws=client.newWebSocket(req,object:WebSocketListener(){
  override fun onOpen(w:WebSocket,r:Response){ listOf("level2","market_trades","heartbeats").forEach{ ch->w.send(JSONObject().put("type","subscribe").put("product_ids",org.json.JSONArray().put(product)).put("channel",ch).toString()) } }
  override fun onMessage(w:WebSocket,text:String){ try{ parse(JSONObject(text)) }catch(_:Exception){} }
  override fun onFailure(w:WebSocket,t:Throwable,r:Response?){ update(Snapshot(product,0.0,0.0,0.0,0,"WAIT",0,0.0,0.0,"Feed reconnect required: ${t.message}")) }
 }) }
 fun stop(){ws?.close(1000,"stop")}
 private fun parse(j:JSONObject){ val channel=j.optString("channel"); val events=j.optJSONArray("events")?:return
  for(i in 0 until events.length()){ val e=events.getJSONObject(i)
   if(channel=="l2_data"){ val u=e.optJSONArray("updates")?:continue; for(k in 0 until u.length()){ val x=u.getJSONObject(k); val side=x.optString("side"); val p=x.optDouble("price_level"); val q=x.optDouble("new_quantity"); val book=if(side=="bid") bids else asks; val key="$side:$p"; val old=book[p]?:0.0; changes++; if(old>0&&q==0.0)cancels++; if(q==0.0)book.remove(p) else book[p]=q; prior[key]=q to System.currentTimeMillis() }; emit() }
   if(channel=="market_trades"){ val ts=e.optJSONArray("trades")?:continue; for(k in 0 until ts.length()){ val t=ts.getJSONObject(k); val q=t.optDouble("size"); if(t.optString("side")=="BUY") sellFlow+=q else buyFlow+=q }; emit() }
  }
 }
 private fun emit(){ if(bids.isEmpty()||asks.isEmpty())return; val bid=bids.firstKey(); val ask=asks.firstKey(); val mid=(bid+ask)/2; val depthPct=.006
  val bd=bids.filterKeys{it>=mid*(1-depthPct)}.values.sum(); val ad=asks.filterKeys{it<=mid*(1+depthPct)}.values.sum(); val imb=if(bd+ad==0.0)0.0 else (bd-ad)/(bd+ad)
  val cancelRatio=if(changes==0)0.0 else cancels.toDouble()/changes; val maxBid=bids.entries.take(15).maxOfOrNull{it.value}?:0.0; val maxAsk=asks.entries.take(15).maxOfOrNull{it.value}?:0.0; val median=(bids.values.take(15)+asks.values.take(15)).sorted().let{if(it.isEmpty())1.0 else it[it.size/2].coerceAtLeast(1e-9)}
  val wall=max(maxBid,maxAsk)/median; val spoof=(cancelRatio*55 + min(1.0,wall/12.0)*45).roundToInt().coerceIn(0,100); val flow=if(buyFlow+sellFlow==0.0)0.0 else (buyFlow-sellFlow)/(buyFlow+sellFlow)
  val score=(imb*.55+flow*.45); val conf=(abs(score)*100).roundToInt().coerceIn(0,95); val signal=when{score>.28&&spoof<75->"LONG WATCH";score<-.28&&spoof<75->"EXIT / SHORT WATCH";else->"WAIT"}; val range=(ask-bid).coerceAtLeast(mid*.0015)
  update(Snapshot(product,bid,ask,imb,spoof,signal,conf,if(score>=0)mid+range*4 else mid-range*4,if(score>=0)mid-range*3 else mid+range*3,"LIVE"))
 }
}
