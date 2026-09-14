(()=>{
  const KEY='twistedPsycheTradeCallsV4';
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  const avg=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0;
  const med=a=>{const s=[...a].sort((x,y)=>x-y);return s.length?s[Math.floor(s.length/2)]:0};
  const st={book:new Map(),events:[],lastRecord:0};
  const load=()=>{try{return JSON.parse(localStorage.getItem(KEY)||'[]')}catch{return[]}};
  const save=x=>{try{localStorage.setItem(KEY,JSON.stringify(x.slice(-500)))}catch{}};
  const pct=x=>`${(x*100).toFixed(2)}%`;
  const wilson=(wins,n,z=1.645)=>{if(!n)return 0;const p=wins/n,z2=z*z,d=1+z2/n;return clamp((p+z2/(2*n)-z*Math.sqrt((p*(1-p)+z2/(4*n))/n))/d,0,1)};
  const text=id=>document.getElementById(id)?.textContent?.trim()||'';
  const firstNum=id=>{const m=text(id).match(/-?\d+(?:\.\d+)?/);return m?Number(m[0]):NaN};

  function ensure(){
    if(document.getElementById('actorLedgerCard'))return;
    const anchor=document.getElementById('liqHuntCard')||document.querySelector('.section.trade');if(!anchor)return;
    const el=document.createElement('section');el.id='actorLedgerCard';el.className='section';
    el.innerHTML=`<div class="section-title">Frozen trade calls • actor fingerprint • accuracy</div>
      <div id="actorState" class="signal warn">LEARNING BEHAVIOR</div>
      <div id="actorWhy" class="path">Live forecast keeps moving. Qualified trade calls below are frozen forever at issue time.</div>
      <div class="metrics">
        <div class="metric"><div class="metric-label">Same-strategy confidence</div><div id="actorConf" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Likely next action</div><div id="actorNext" class="metric-value">—</div></div>
        <div class="metric"><div class="metric-label">Trade-call zone hit rate</div><div id="ledgerZone" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Trade-call target success</div><div id="ledgerWin" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Conservative success floor</div><div id="ledgerFloor" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Probability calibration</div><div id="ledgerCalibration" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Avg favorable / adverse</div><div id="ledgerExcursion" class="metric-value">Learning</div></div>
        <div class="metric"><div class="metric-label">Resolved trade calls</div><div id="ledgerCount" class="metric-value">0</div></div>
      </div>
      <div id="frozenCall" class="callout muted">NO QUALIFIED TRADE CALL FROZEN YET. Live forecast changes are not counted as trades.</div>
      <div id="ledgerGrade" class="callout muted">Evidence grade: LEARNING — qualified calls only.</div>
      <details class="details" open><summary>Frozen qualified trade calls</summary><div id="ledgerRecent" class="small">Waiting for first qualified trade call…</div></details>`;
    anchor.insertAdjacentElement('afterend',el);
  }

  function actor(mid,now){
    const bp=[...bids.keys()].filter(p=>p>=mid*.994&&p<=mid).sort((a,b)=>b-a).slice(0,50),ap=[...asks.keys()].filter(p=>p<=mid*1.006&&p>=mid).sort((a,b)=>a-b).slice(0,50);
    const bm=med(bp.map(p=>bids.get(p)||0))||1,am=med(ap.map(p=>asks.get(p)||0))||1;
    let add=0,remove=0,replenish=0,largeAdds=0,largeCancels=0,levels=new Set();const cur=new Map();
    for(const [side,map,base] of [['b',bids,bm],['a',asks,am]])for(const [p,q] of map){if(Math.abs(p-mid)/mid>.006)continue;const k=side+':'+p,old=st.book.get(k)||0;cur.set(k,q);const d=q-old;if(d>0){add+=d;if(d>=base*1.8){largeAdds++;levels.add(side+':'+Math.round((p/mid-1)*10000));}if(old>0)replenish+=d}else if(d<0){remove-=d;if(old>=base*1.8&&q<old*.25)largeCancels++;}}
    for(const [k,old] of st.book)if(!cur.has(k)){remove+=old;largeCancels++;}st.book=cur;
    const rt=trades.filter(t=>now-t.t<=2500),buy=rt.filter(t=>t.buy).reduce((s,t)=>s+t.q,0),sell=rt.filter(t=>!t.buy).reduce((s,t)=>s+t.q,0),flow=(buy-sell)/(buy+sell||1);
    st.events.push({t:now,largeAdds,largeCancels,replenish,remove,flow,levels:levels.size});while(st.events.length&&now-st.events[0].t>30000)st.events.shift();
    const e10=st.events.filter(x=>now-x.t<=10000),cancelRate=e10.length?avg(e10.map(x=>x.largeCancels/(x.largeAdds+x.largeCancels+1))):0,refill=clamp(avg(e10.map(x=>x.replenish/(x.replenish+x.remove+1e-9))),0,1),layer=clamp(avg(e10.map(x=>Math.min(1,x.levels/4))),0,1),repeat=clamp(e10.length/30,0,1);
    let name='PROBING',next='WAIT FOR COMMITMENT',why='Small/reversible book changes dominate.';
    if(flow<-.55&&remove>add*1.2){name='SWEEPING';next='WATCH FOR ABSORPTION OR CONTINUATION';why='Aggressive selling and liquidity removal are accelerating together.'}
    else if(flow<-.20&&refill>.58){name='ABSORBING';next='POSSIBLE RELEASE / REVERSAL';why='Selling continues while bid liquidity repeatedly refills.'}
    else if(refill>.68&&largeAdds>=2){name='DEFENDING';next='HOLD LEVEL OR ACCUMULATE';why='Large bid-side liquidity is repeatedly replenishing.'}
    else if(flow>.35&&refill>.48){name='LOADING';next='CONTINUE ACCUMULATION / MARK UP';why='Aggressive buyers and persistent refill suggest accumulation.'}
    else if(cancelRate>.42&&layer>.35){name='LAYERING / SPOOF-LIKE';next='WALL MAY MOVE OR VANISH';why='Large displayed liquidity repeatedly cancels across multiple levels.'}
    else if(flow>.55&&remove>add){name='RELEASING';next='PRICE EXPANSION UP';why='Buy aggression is high while opposing liquidity is being removed.'}
    else if(flow<-.25&&largeAdds>=2&&cancelRate<.25){name='DISTRIBUTING';next='CONTINUE SELLING INTO BIDS';why='Repeated large liquidity with persistent sell flow suggests distribution.'}
    const conf=Math.round(clamp(.28+repeat*.22+Math.abs(flow)*.22+refill*.14+layer*.08+cancelRate*.12,0,.96)*100);return{name,next,why,conf,flow};
  }

  function currentHunt(){
    const num=id=>{const m=text(id).match(/-?\d+(?:\.\d+)?/g);return m?m.map(Number):[]};const z=num('lhZone'),e=num('lhEntry'),s=num('lhSweep'),a=num('lhAbort'),t=num('lhTarget');
    return z.length>=2&&e.length&&s.length&&a.length&&t.length?{zoneLow:z[0],zoneHigh:z[1],entry:e[0],prob:s[0],abort:a[0],target:t[0],action:text('lhAction'),absorption:firstNum('lhAbsorb')}:null;
  }

  function qualified(h,a){
    if(!h)return{ok:false};const d=text('decision').toUpperCase(),profitTxt=text('profit'),rrTxt=text('rr');
    const profit=Number((profitTxt.match(/[-+]?\$?(\d+(?:\.\d+)?)/)||[])[1]||NaN)*(profitTxt.trim().startsWith('-')?-1:1);
    const rr=Number((rrTxt.match(/(\d+(?:\.\d+)?)\s+TO\s+1/i)||[])[1]||NaN);
    const baseBuy=d==='BUY NOW';
    const huntBuy=h.action.startsWith('EARLY REVERSAL')&&h.prob>=68&&h.absorption>=65&&a.name!=='SWEEPING';
    const actorBuy=a.name==='RELEASING'&&a.conf>=70&&h.prob>=60;
    const costsOk=Number.isFinite(profit)&&profit>0&&Number.isFinite(rr)&&rr>=1;
    return{ok:(baseBuy||huntBuy||actorBuy)&&costsOk&&!d.startsWith('WAIT'),call:baseBuy?'BUY NOW':huntBuy?'EARLY REVERSAL WINDOW':'ACTOR RELEASE',profit,rr,decision:d};
  }

  function updateLedger(mid,a,now){
    const product=(text('pair')||'BTC-USD').toUpperCase(),h=currentHunt();let rows=load();
    for(const r of rows){if(r.product!==product||r.result!=='PENDING')continue;r.lowSeen=Math.min(r.lowSeen??mid,mid);r.highSeen=Math.max(r.highSeen??mid,mid);if(mid<=r.zoneHigh&&!r.zoneTouched){r.zoneTouched=true;r.zoneTouchedAt=now;}if(mid<=r.abort){r.result='FAILED';r.resolvedAt=now;r.note='Abort hit before target';}else if(r.zoneTouched&&mid>=r.target){r.result='HIT';r.resolvedAt=now;r.note='Sweep/entry zone reached, then frozen target hit';}else if(now-r.createdAt>20*60*1000){r.result='EXPIRED';r.resolvedAt=now;r.note=r.zoneTouched?'Entry zone touched; frozen target not hit within 20m':'Frozen entry zone never reached within 20m';}if(r.entry>0){r.mfePct=(r.highSeen-r.entry)/r.entry;r.maePct=(r.lowSeen-r.entry)/r.entry;}}
    const q=qualified(h,a),active=rows.find(r=>r.product===product&&r.result==='PENDING');
    if(q.ok&&!active&&now-st.lastRecord>30000){rows.push({id:`TC-${now}`,kind:'TRADE_CALL',createdAt:now,product,call:q.call,marketPrice:mid,zoneLow:h.zoneLow,zoneHigh:h.zoneHigh,entry:h.entry,abort:h.abort,target:h.target,sweepProbability:h.prob,absorption:h.absorption,actorState:a.name,actorConfidence:a.conf,estimatedProfit:q.profit,rewardRisk:q.rr,result:'PENDING',zoneTouched:false,lowSeen:mid,highSeen:mid});st.lastRecord=now;}
    save(rows);rows=load();const resolved=rows.filter(r=>r.result!=='PENDING'),n=resolved.length,zoneHits=resolved.filter(r=>r.zoneTouched).length,wins=resolved.filter(r=>r.result==='HIT').length,floor=wilson(wins,n),mfe=resolved.map(r=>r.mfePct).filter(Number.isFinite),mae=resolved.map(r=>r.maePct).filter(Number.isFinite);
    const brier=n?avg(resolved.map(r=>Math.pow((r.sweepProbability||50)/100-(r.zoneTouched?1:0),2))):null;
    $('ledgerZone').textContent=n?`${Math.round(zoneHits/n*100)}% (${zoneHits}/${n})`:'Learning';$('ledgerWin').textContent=n?`${Math.round(wins/n*100)}% (${wins}/${n})`:'Learning';$('ledgerFloor').textContent=n?`${Math.round(floor*100)}% lower bound`:'Learning';$('ledgerCalibration').textContent=brier==null?'Learning':`Brier ${brier.toFixed(3)} • lower is better`; $('ledgerExcursion').textContent=mfe.length?`${pct(avg(mfe))} / ${pct(avg(mae))}`:'Learning';$('ledgerCount').textContent=String(n);
    const latest=[...rows].sort((x,y)=>y.createdAt-x.createdAt)[0];if(latest)$('frozenCall').innerHTML=`<b>${latest.id} • ${latest.product} • ${latest.call} • ${latest.result}</b><br>Issued ${new Date(latest.createdAt).toLocaleTimeString()} at ${fmt(latest.marketPrice)}<br>FROZEN zone ${fmt(latest.zoneLow)}–${fmt(latest.zoneHigh)} • entry ${fmt(latest.entry)} • abort ${fmt(latest.abort)} • target ${fmt(latest.target)}<br>FROZEN P=${latest.sweepProbability}% • absorption ${latest.absorption}% • actor ${latest.actorState} ${latest.actorConfidence}% • est. profit $${Number(latest.estimatedProfit||0).toFixed(2)} • R/R ${Number(latest.rewardRisk||0).toFixed(2)}:1`;
    const grade=n<30?'LEARNING':n<100?'EARLY EVIDENCE':floor>=.55&&brier!=null&&brier<=.22?'PROMISING EDGE':'MIXED / UNPROVEN';$('ledgerGrade').textContent=`Evidence grade: ${grade} • qualified frozen trade calls only; live forecast changes are excluded.`;
    const rec=[...rows].sort((x,y)=>y.createdAt-x.createdAt).slice(0,12);$('ledgerRecent').innerHTML=rec.length?rec.map(r=>`${r.id} • ${new Date(r.createdAt).toLocaleTimeString()} • ${r.product} • ${r.call} • ${r.result}<br>&nbsp;&nbsp;price ${fmt(r.marketPrice)} • zone ${fmt(r.zoneLow)}–${fmt(r.zoneHigh)} • entry ${fmt(r.entry)} • abort ${fmt(r.abort)} • target ${fmt(r.target)} • P ${r.sweepProbability}%${Number.isFinite(r.mfePct)?` • MFE ${pct(r.mfePct)} / MAE ${pct(r.maePct)}`:''}`).join('<br>'):'Waiting for first qualified trade call…';
  }

  function tick(){try{ensure();if(!running||!bids.size||!asks.size)return;const bp=[...bids.keys()].sort((x,y)=>y-x),ap=[...asks.keys()].sort((x,y)=>x-y),mid=(bp[0]+ap[0])/2,now=Date.now(),a=actor(mid,now);$('actorState').textContent=a.name;$('actorState').className='signal '+(a.name==='ABSORBING'||a.name==='LOADING'||a.name==='RELEASING'?'good':a.name.includes('SPOOF')||a.name==='SWEEPING'?'warn':'');$('actorWhy').textContent=a.why;$('actorConf').textContent=`${a.conf}% • behavioral match only`;$('actorNext').textContent=a.next;updateLedger(mid,a,now);}catch(e){console.warn('trade call ledger',e)}}
  ensure();setInterval(tick,250);
})();