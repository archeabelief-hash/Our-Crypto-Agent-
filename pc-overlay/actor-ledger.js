(()=>{
  const KEY='twistedPsychePredictionLedgerV2';
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  const avg=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0;
  const med=a=>{const s=[...a].sort((x,y)=>x-y);return s.length?s[Math.floor(s.length/2)]:0};
  const st={book:new Map(),events:[],lastActor:null,lastRecord:0};
  const load=()=>{try{return JSON.parse(localStorage.getItem(KEY)||'[]')}catch{return[]}};
  const save=x=>{try{localStorage.setItem(KEY,JSON.stringify(x.slice(-250)))}catch{}};

  function ensure(){
    if(document.getElementById('actorLedgerCard'))return;
    const anchor=document.getElementById('liqHuntCard')||document.querySelector('.section.trade');if(!anchor)return;
    const el=document.createElement('section');el.id='actorLedgerCard';el.className='section';
    el.innerHTML=`<div class="section-title">Actor fingerprint • prediction accuracy</div>
      <div id="actorState" class="signal warn">LEARNING BEHAVIOR</div>
      <div id="actorWhy" class="path">Watching cancellation, refill, layering and execution rhythm.</div>
      <div class="metrics">
        <div class="metric"><div class="metric-label">Same-strategy confidence</div><div id="actorConf" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Likely next action</div><div id="actorNext" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Sweep-zone hit rate</div><div id="ledgerZone" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Sweep→bounce success</div><div id="ledgerWin" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Resolved predictions</div><div id="ledgerCount" class="metric-value">0</div></div>
        <div class="metric"><div class="metric-label">Latest result</div><div id="ledgerLatest" class="metric-value">No resolved calls yet</div></div>
      </div>
      <details class="details"><summary>Recent frozen predictions</summary><div id="ledgerRecent" class="small">No predictions recorded yet.</div></details>`;
    anchor.insertAdjacentElement('afterend',el);
  }

  function actor(mid,now){
    const bp=[...bids.keys()].filter(p=>p>=mid*.994&&p<=mid).sort((a,b)=>b-a).slice(0,50),ap=[...asks.keys()].filter(p=>p<=mid*1.006&&p>=mid).sort((a,b)=>a-b).slice(0,50);
    const bm=med(bp.map(p=>bids.get(p)||0))||1,am=med(ap.map(p=>asks.get(p)||0))||1;
    let add=0,remove=0,replenish=0,largeAdds=0,largeCancels=0,levels=new Set();
    const cur=new Map();
    for(const [side,map,base] of [['b',bids,bm],['a',asks,am]])for(const [p,q] of map){if(Math.abs(p-mid)/mid>.006)continue;const k=side+':'+p,old=st.book.get(k)||0;cur.set(k,q);const d=q-old;if(d>0){add+=d;if(d>=base*1.8){largeAdds++;levels.add(side+':'+Math.round((p/mid-1)*10000));}if(old>0)replenish+=d}else if(d<0){remove-=d;if(old>=base*1.8&&q<old*.25)largeCancels++;}}
    for(const [k,old] of st.book)if(!cur.has(k)){remove+=old;largeCancels++;}
    st.book=cur;
    const rt=trades.filter(t=>now-t.t<=2500),buy=rt.filter(t=>t.buy).reduce((s,t)=>s+t.q,0),sell=rt.filter(t=>!t.buy).reduce((s,t)=>s+t.q,0),flow=(buy-sell)/(buy+sell||1);
    st.events.push({t:now,largeAdds,largeCancels,replenish,remove,flow,levels:levels.size});while(st.events.length&&now-st.events[0].t>30000)st.events.shift();
    const e10=st.events.filter(x=>now-x.t<=10000),cancelRate=e10.length?avg(e10.map(x=>x.largeCancels/(x.largeAdds+x.largeCancels+1))):0,refill=clamp(avg(e10.map(x=>x.replenish/(x.replenish+x.remove+1e-9))),0,1),layer=clamp(avg(e10.map(x=>Math.min(1,x.levels/4))),0,1),repeat=clamp(e10.length/30,0,1);
    let name='PROBING',next='WAIT FOR COMMITMENT',why='Small/reversible book changes dominate.';
    if(flow<-.55&&remove>add*1.2){name='SWEEPING',next='WATCH FOR ABSORPTION OR CONTINUATION',why='Aggressive selling and liquidity removal are accelerating together.'}
    else if(flow<-.20&&refill>.58){name='ABSORBING',next='POSSIBLE RELEASE / REVERSAL',why='Selling continues while bid liquidity repeatedly refills.'}
    else if(refill>.68&&largeAdds>=2){name='DEFENDING',next='HOLD LEVEL OR ACCUMULATE',why='Large bid-side liquidity is repeatedly replenishing.'}
    else if(flow>.35&&refill>.48){name='LOADING',next='CONTINUE ACCUMULATION / MARK UP',why='Aggressive buyers and persistent refill suggest accumulation.'}
    else if(cancelRate>.42&&layer>.35){name='LAYERING / SPOOF-LIKE',next='WALL MAY MOVE OR VANISH',why='Large displayed liquidity repeatedly cancels across multiple levels.'}
    else if(flow>.55&&remove>add){name='RELEASING',next='PRICE EXPANSION UP',why='Buy aggression is high while opposing liquidity is being removed.'}
    else if(flow<-.25&&largeAdds>=2&&cancelRate<.25){name='DISTRIBUTING',next='CONTINUE SELLING INTO BIDS',why='Repeated large liquidity with persistent sell flow suggests distribution.'}
    const conf=Math.round(clamp(.28+repeat*.22+Math.abs(flow)*.22+refill*.14+layer*.08+cancelRate*.12,0,0.96)*100);
    return{name,next,why,conf,cancelRate,refill,layer,flow};
  }

  function currentHunt(){
    const num=id=>{const t=document.getElementById(id)?.textContent||'';const m=t.match(/-?\d+(?:\.\d+)?/g);return m?m.map(Number):[]};
    const z=num('lhZone'),e=num('lhEntry'),s=num('lhSweep'),a=num('lhAbort'),t=num('lhTarget');
    return z.length>=2&&e.length&&s.length&&a.length&&t.length?{zoneLow:z[0],zoneHigh:z[1],entry:e[0],prob:s[0],abort:a[0],target:t[0],action:document.getElementById('lhAction')?.textContent||''}:null;
  }

  function updateLedger(mid,actorState,now){
    const product=($('pair')?.value||'BTC-USD').trim().toUpperCase(),h=currentHunt();let rows=load();
    for(const r of rows){if(r.product!==product||r.result!=='PENDING')continue;r.lowSeen=Math.min(r.lowSeen??mid,mid);r.highSeen=Math.max(r.highSeen??mid,mid);if(mid<=r.zoneHigh)r.zoneTouched=true;
      if(mid<=r.abort){r.result='FAILED';r.resolvedAt=now;r.note='Abort level hit before bounce objective';}
      else if(r.zoneTouched&&mid>=r.target){r.result='HIT';r.resolvedAt=now;r.note='Sweep zone touched, then bounce objective reached';}
      else if(now-r.createdAt>20*60*1000){r.result='EXPIRED';r.resolvedAt=now;r.note=r.zoneTouched?'Zone touched but bounce objective not reached in 20m':'Sweep zone not reached in 20m';}
    }
    const active=rows.find(r=>r.product===product&&r.result==='PENDING');
    if(h&&h.prob>=68&&!active&&now-st.lastRecord>30000){rows.push({id:now+'-'+product,createdAt:now,product,zoneLow:h.zoneLow,zoneHigh:h.zoneHigh,entry:h.entry,abort:h.abort,target:h.target,sweepProbability:h.prob,actorState:actorState.name,actorConfidence:actorState.conf,result:'PENDING',zoneTouched:false,lowSeen:mid,highSeen:mid});st.lastRecord=now;}
    save(rows);rows=load();
    const resolved=rows.filter(r=>r.result!=='PENDING'),zoneResolved=resolved.filter(r=>r.result!=='EXPIRED'||r.zoneTouched),zoneHits=resolved.filter(r=>r.zoneTouched).length,wins=resolved.filter(r=>r.result==='HIT').length,fails=resolved.filter(r=>r.result==='FAILED'||r.result==='EXPIRED').length;
    if($('ledgerZone'))$('ledgerZone').textContent=resolved.length?`${Math.round(zoneHits/resolved.length*100)}% (${zoneHits}/${resolved.length})`:'Learning';
    if($('ledgerWin'))$('ledgerWin').textContent=(wins+fails)?`${Math.round(wins/(wins+fails)*100)}% (${wins}/${wins+fails})`:'Learning';
    if($('ledgerCount'))$('ledgerCount').textContent=String(resolved.length);
    const latest=[...resolved].sort((a,b)=>(b.resolvedAt||0)-(a.resolvedAt||0))[0];if($('ledgerLatest'))$('ledgerLatest').textContent=latest?`${latest.result} • ${latest.product} • ${latest.note}`:'No resolved calls yet';
    const rec=[...rows].sort((a,b)=>b.createdAt-a.createdAt).slice(0,8);if($('ledgerRecent'))$('ledgerRecent').innerHTML=rec.length?rec.map(r=>`${new Date(r.createdAt).toLocaleTimeString()} ${r.product} • ${r.result} • zone ${fmt(r.zoneLow)}–${fmt(r.zoneHigh)} • entry ${fmt(r.entry)} • target ${fmt(r.target)} • actor ${r.actorState} ${r.actorConfidence}%`).join('<br>'):'No predictions recorded yet.';
  }

  function tick(){try{ensure();if(!running||!bids.size||!asks.size)return;const bp=[...bids.keys()].sort((a,b)=>b-a),ap=[...asks.keys()].sort((a,b)=>a-b),mid=(bp[0]+ap[0])/2,now=Date.now(),a=actor(mid,now);st.lastActor=a;
    $('actorState').textContent=a.name;$('actorState').className='signal '+(a.name==='ABSORBING'||a.name==='LOADING'||a.name==='RELEASING'?'good':a.name.includes('SPOOF')||a.name==='SWEEPING'?'warn':'');$('actorWhy').textContent=a.why;$('actorConf').textContent=`${a.conf}% • behavioral match only`;$('actorNext').textContent=a.next;updateLedger(mid,a,now);
  }catch(e){console.warn('actor ledger',e)}}
  ensure();setInterval(tick,250);
})();