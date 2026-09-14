package com.cryptoai.overlay

import java.util.ArrayDeque
import kotlin.math.*

class ActorStateModel {
    data class Result(
        val state: String,
        val confidence: Int,
        val nextAction: String,
        val reason: String,
        val cancelRate: Int,
        val refillRate: Int,
        val layeringScore: Int
    )

    private data class BookEvent(val t: Long, val side: String, val price: Double, val oldQty: Double, val newQty: Double)
    private data class TradeEvent(val t: Long, val size: Double, val aggressiveBuy: Boolean)
    private val book = ArrayDeque<BookEvent>()
    private val trades = ArrayDeque<TradeEvent>()

    @Synchronized fun onBookUpdate(side: String, price: Double, oldQty: Double, newQty: Double, now: Long) {
        book.addLast(BookEvent(now, side, price, oldQty, newQty)); trim(now)
    }

    @Synchronized fun onTrade(size: Double, aggressiveBuy: Boolean, now: Long) {
        if (size > 0.0) trades.addLast(TradeEvent(now, size, aggressiveBuy)); trim(now)
    }

    @Synchronized fun evaluate(mid: Double, bids: Map<Double, Double>, asks: Map<Double, Double>): Result {
        val now = System.currentTimeMillis(); trim(now)
        fun median(xs: List<Double>): Double { if (xs.isEmpty()) return 1.0; val s=xs.sorted(); return s[s.size/2].coerceAtLeast(1e-9) }
        val bm=median(bids.filterKeys { it>=mid*.994 && it<=mid }.values.toList())
        val am=median(asks.filterKeys { it<=mid*1.006 && it>=mid }.values.toList())
        val recent=book.filter { now-it.t<=10_000 && mid>0 && abs(it.price-mid)/mid<=.006 }
        var largeAdds=0; var largeCancels=0; var refill=0.0; var removed=0.0; val layers=mutableSetOf<String>()
        for(e in recent){ val base=if(e.side=="bid") bm else am; val d=e.newQty-e.oldQty
            if(d>0){ if(d>=base*1.8){ largeAdds++; layers += e.side+":"+round((e.price/mid-1)*10000).toInt() }; if(e.oldQty>0) refill+=d }
            else if(d<0){ removed+=-d; if(e.oldQty>=base*1.8 && e.newQty<e.oldQty*.25) largeCancels++ }
        }
        val rt=trades.filter { now-it.t<=2500 }; val buys=rt.filter{it.aggressiveBuy}.sumOf{it.size}; val sells=rt.filter{!it.aggressiveBuy}.sumOf{it.size}; val flow=if(buys+sells==0.0)0.0 else (buys-sells)/(buys+sells)
        val cancelRate=(largeCancels.toDouble()/(largeAdds+largeCancels+1)).coerceIn(0.0,1.0)
        val refillRate=(refill/(refill+removed+1e-9)).coerceIn(0.0,1.0)
        val layering=(layers.size/4.0).coerceIn(0.0,1.0)
        var state="PROBING"; var next="WAIT FOR COMMITMENT"; var why="Small or reversible order-book changes dominate."
        if(flow<-.55 && removed>refill*1.2){state="SWEEPING";next="WATCH FOR ABSORPTION OR CONTINUATION";why="Aggressive selling and liquidity removal are accelerating together."}
        else if(flow<-.20 && refillRate>.58){state="ABSORBING";next="POSSIBLE RELEASE / REVERSAL";why="Selling continues while bid liquidity repeatedly refills."}
        else if(refillRate>.68 && largeAdds>=2){state="DEFENDING";next="HOLD LEVEL OR ACCUMULATE";why="Large liquidity is repeatedly replenishing around the same zone."}
        else if(flow>.35 && refillRate>.48){state="LOADING";next="CONTINUE ACCUMULATION / MARK UP";why="Aggressive buyers and persistent refill suggest accumulation."}
        else if(cancelRate>.42 && layering>.35){state="LAYERING / SPOOF-LIKE";next="WALL MAY MOVE OR VANISH";why="Large displayed liquidity repeatedly cancels across several levels."}
        else if(flow>.55 && removed>refill){state="RELEASING";next="PRICE EXPANSION UP";why="Buy aggression is high while opposing liquidity is being removed."}
        else if(flow<-.25 && largeAdds>=2 && cancelRate<.25){state="DISTRIBUTING";next="CONTINUE SELLING INTO BIDS";why="Persistent sell flow is interacting with repeated large liquidity."}
        val samples=(recent.size/25.0).coerceIn(0.0,1.0)
        val conf=(.28+samples*.22+abs(flow)*.22+refillRate*.14+layering*.08+cancelRate*.12).coerceIn(0.0,.96)
        return Result(state,(conf*100).roundToInt(),next,why,(cancelRate*100).roundToInt(),(refillRate*100).roundToInt(),(layering*100).roundToInt())
    }

    private fun trim(now: Long){ while(book.isNotEmpty()&&now-book.first().t>30_000)book.removeFirst(); while(trades.isNotEmpty()&&now-trades.first().t>30_000)trades.removeFirst() }
}
