(()=>{
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  const avg=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0;
  const med=a=>{const s=[...a].sort((x,y)=>x-y);return s.length?s[Math.floor(s.length/2)]:0};
  const state={lastBook:new Map(),samples:[],lastMid:0,lastAt:0};

  function ensureCard(){
    if(document.getElementById('liqHuntCard'))return;
    const host=document.querySelector('.section.forecast')||document.querySelector('.wrap');if(!host)return;
    const el=document.createElement('section');el.id='liqHuntCard';el.className='section trade';
    el.innerHTML=`<div class="section-title">Liquidity hunt • pre-wick entry engine</div>
      <div id="lhAction" class="signal warn">WARMING UP</div>
      <div id="lhWhy" class="path">Reading raw order-book changes and executed trades.</div>
      <div class="metrics">
        <div class="metric"><div class="metric-label">Likely sweep zone</div><div id="lhZone" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Sniper / pre-entry</div><div id="lhEntry" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Sweep probability</div><div id="lhSweep" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Absorption / exhaustion</div><div id="lhAbsorb" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Abort below</div><div id="lhAbort" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Bounce objective</div><div id="lhTarget" class="metric-value">—</div></div>
      </div><div class="small" style="margin-top:6px">Estimated zones from public market microstructure — not private stop-order data and not a guarantee.</div>`;
    host.insertAdjacentElement('afterend',el);
  }

  function readBook(mid){
    const bp=[...bids.keys()].filter(p=>p<=mid&&p>=mid*.985).sort((a,b)=>b-a).slice(0,120);
    const ap=[...asks.keys()].filter(p=>p>=mid&&p<=mid*1.015).sort((a,b)=>a-b).slice(0,120);
    const bidQs=bp.map(p=>bids.get(p)||0),askQs=ap.map(p=>asks.get(p)||0),bm=med(bidQs)||1,am=med(askQs)||1;
    const bidWalls=bp.map(p=>({p,q:bids.get(p)||0,r:(bids.get(p)||0)/bm})).filter(x=>x.r>=1.6);
    const askWalls=ap.map(p=>({p,q:asks.get(p)||0,r:(asks.get(p)||0)/am})).filter(x=>x.r>=1.6);
    const strongestBid=(bidWalls.sort((x,y)=>y.r-x.r)[0]||{p:bp[0]||mid,r:1});
    const strongestAsk=(askWalls.sort((x,y)=>y.r-x.r)[0]||{p:ap[0]||mid,r:1});
    return{bp,ap,strongestBid,strongestAsk,bm,am};
  }

  function bookDelta(mid){
    let bidAdd=0,bidRemove=0,askAdd=0,askRemove=0,events=0;
    const current=new Map();
    for(const [side,map] of [['b',bids],['a',asks]])for(const [p,q] of map){
      if(Math.abs(p-mid)/mid>.008)continue;const k=side+':'+p,old=state.lastBook.get(k)||0;current.set(k,q);
      if(old){const d=q-old;if(d>0){side==='b'?bidAdd+=d:askAdd+=d}else if(d<0){side==='b'?bidRemove-=d:askRemove-=d}events++}
    }
    for(const [k,old] of state.lastBook)if(!current.has(k)){if(k[0]==='b')bidRemove+=old;else askRemove+=old;events++}
    state.lastBook=current;return{bidAdd,bidRemove,askAdd,askRemove,events};
  }

  function tick(){
    try{
      ensureCard();if(!running||!bids.size||!asks.size)return;
      const bp=[...bids.keys()].sort((a,b)=>b-a),ap=[...asks.keys()].sort((a,b)=>a-b),bid=bp[0],ask=ap[0],mid=(bid+ask)/2,now=Date.now();
      const book=readBook(mid),d=bookDelta(mid),rt=trades.filter(t=>now-t.t<=3500),buy=rt.filter(t=>t.buy).reduce((s,t)=>s+t.q,0),sell=rt.filter(t=>!t.buy).reduce((s,t)=>s+t.q,0),flow=(buy-sell)/(buy+sell||1);
      const shortTrades=trades.filter(t=>now-t.t<=1000),buy1=shortTrades.filter(t=>t.buy).reduce((s,t)=>s+t.q,0),sell1=shortTrades.filter(t=>!t.buy).reduce((s,t)=>s+t.q,0),flow1=(buy1-sell1)/(buy1+sell1||1);
      const vel=state.lastMid>0&&now-state.lastAt>0?(mid-state.lastMid)/state.lastMid/(now-state.lastAt)*1000:0;state.lastMid=mid;state.lastAt=now;
      state.samples.push({t:now,mid,flow,flow1,vel,bidAdd:d.bidAdd,bidRemove:d.bidRemove,askAdd:d.askAdd,askRemove:d.askRemove});while(state.samples.length&&now-state.samples[0].t>12000)state.samples.shift();
      const prev=state.samples.filter(x=>now-x.t>1800&&now-x.t<7000),prevSell=prev.length?avg(prev.map(x=>Math.max(0,-x.flow1))):0,prevVel=prev.length?avg(prev.map(x=>Math.min(0,x.vel))):0;
      const atr=Math.max(candle.atrPct||.0014,.0007),support=book.strongestBid.p||mid*(1-atr),resistance=book.strongestAsk.p||mid*(1+atr);
      const stopPad=Math.max(mid*.00035,atr*mid*.24),zoneHigh=Math.min(mid,support+stopPad*.30),zoneLow=Math.max(0,support-stopPad*1.15);
      const dist=(mid-zoneHigh)/mid,approach=clamp(1-dist/Math.max(atr*1.8,.001),0,1),sellPressure=clamp(-flow*.65+Math.max(0,-vel)/.0015*.35,0,1);
      const replenishment=(d.bidAdd)/(d.bidAdd+d.bidRemove+1e-9),priceStall=prevVel<0?clamp(1-Math.abs(Math.min(0,vel))/Math.max(Math.abs(prevVel),1e-8),0,1):0;
      const flowRecovery=clamp((flow1+prevSell)/1.5,0,1),cross=(()=>{const vals=[];for(const[n,v]of Object.entries(venue))if(n!=='Coinbase'&&v&&now-v.t<5000)vals.push(((v.bid+v.ask)/2-mid)/mid);return vals.length?avg(vals):0})();
      const crossStall=clamp(.5+cross/.002,0,1),absorption=clamp(replenishment*.34+priceStall*.26+flowRecovery*.25+crossStall*.15,0,1);
      const sweepProb=clamp(.18+approach*.28+sellPressure*.22+Math.min(book.strongestBid.r/7,.18)+(Math.max(0,-candle.trend)/.004)*.08,0,1);
      const inZone=mid<=zoneHigh&&mid>=zoneLow,below=mid<zoneLow;
      const early=clamp(absorption*.68+(inZone?.20:0)+(flow1>-.12?.12:0),0,1),sniper=zoneLow+(zoneHigh-zoneLow)*.58,abort=Math.max(0,zoneLow-stopPad*.85),target=Math.max(mid+atr*mid*.95,Math.min(resistance,mid+atr*mid*2.2));
      let action='WAIT — TRACKING LIQUIDITY';let why='Price has not reached the estimated stop-sweep area.';
      if(below&&flow1<-.25){action='ABORT — BREAKDOWN STILL ACCELERATING';why='Price pushed through the estimated zone and aggressive selling remains dominant.'}
      else if(inZone&&early>=.72){action='EARLY REVERSAL WINDOW';why='Estimated stop zone reached while sell pressure is stalling and bids are replenishing.'}
      else if(inZone){action='SWEEP IN PROGRESS — WATCH CLOSELY';why='Price is inside the predicted liquidity pocket; waiting for exhaustion to strengthen.'}
      else if(sweepProb>=.68){action='PREPARE SNIPER ENTRY';why='Book structure and sell pressure point toward a likely flush into the zone below.'}
      const pct=x=>Math.round(x*100)+'%';
      $('lhAction').textContent=action;$('lhAction').className='signal '+(action.startsWith('EARLY')?'good':action.startsWith('ABORT')?'bad':'warn');
      $('lhWhy').textContent=why;$('lhZone').textContent=`${fmt(zoneLow)} – ${fmt(zoneHigh)}`;$('lhEntry').textContent=fmt(sniper);$('lhSweep').textContent=pct(sweepProb);$('lhAbsorb').textContent=`${pct(absorption)}${inZone?' • IN ZONE':''}`;$('lhAbort').textContent=fmt(abort);$('lhTarget').textContent=fmt(target);
    }catch(e){console.warn('liquidity hunt',e)}
  }
  ensureCard();setInterval(tick,120);
})();