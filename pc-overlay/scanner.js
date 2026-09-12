(()=>{
  const $s=id=>document.getElementById(id);
  const API='https://api.exchange.coinbase.com';
  const WS='wss://advanced-trade-ws.coinbase.com';
  const FEE=.006;
  const DETAIL_COUNT=18;
  const tickers=new Map();
  let products=[];
  let sock=null;
  let scanning=false;
  let lastDetailed=0;
  let refreshTimer=null;
  const sleep=ms=>new Promise(r=>setTimeout(r,ms));
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  const median=a=>{if(!a.length)return 0;const s=[...a].sort((x,y)=>x-y),m=Math.floor(s.length/2);return s.length%2?s[m]:(s[m-1]+s[m])/2};
  const money=n=>Number.isFinite(n)?`${n>=0?'+':'-'}$${Math.abs(n).toFixed(2)}`:'—';
  const px=n=>!Number.isFinite(n)?'—':n>=1000?n.toFixed(2):n>=1?n.toFixed(4):n.toFixed(7);

  async function jfetch(url){const r=await fetch(url,{cache:'no-store'});if(!r.ok)throw new Error(`HTTP ${r.status}`);return r.json()}

  function setStatus(text,cls='warn'){
    const el=$s('scanStatus');if(!el)return;el.textContent=text;el.className='signal '+cls;
  }

  async function loadProducts(){
    const rows=await jfetch(`${API}/products`);
    products=rows.filter(p=>p.quote_currency==='USD'&&p.status==='online'&&!p.trading_disabled&&!p.cancel_only&&!p.post_only);
    const el=$s('scanUniverse');if(el)el.textContent=`${products.length} live Coinbase USD pairs`;
    return products;
  }

  function closeSocket(){if(sock){try{sock.close()}catch{}sock=null}}

  function openTickerSocket(){
    closeSocket();tickers.clear();
    sock=new WebSocket(WS);
    sock.onopen=()=>{
      const ids=products.map(p=>p.id);
      for(let i=0;i<ids.length;i+=75){
        sock.send(JSON.stringify({type:'subscribe',channel:'ticker',product_ids:ids.slice(i,i+75)}));
      }
    };
    sock.onmessage=e=>{
      try{
        const j=JSON.parse(e.data);
        for(const ev of j.events||[])for(const t of ev.tickers||[]){
          const price=+t.price,bid=+t.best_bid,ask=+t.best_ask,vol=+t.volume_24_h,chg=(+t.price_percent_chg_24_h||0)/100;
          if(price>0)tickers.set(t.product_id,{id:t.product_id,price,bid,ask,vol,chg,t:Date.now()});
        }
      }catch{}
    };
    sock.onerror=()=>setStatus('SCANNER CONNECTION PROBLEM — retrying','bad');
    sock.onclose=()=>{if(products.length)setTimeout(()=>{if(!sock||sock.readyState===WebSocket.CLOSED)openTickerSocket()},2500)};
  }

  function preCandidates(){
    const now=Date.now(),rows=[];
    for(const p of products){
      const t=tickers.get(p.id);if(!t||now-t.t>45000)continue;
      const mid=(t.bid>0&&t.ask>=t.bid)?(t.bid+t.ask)/2:t.price;
      const spread=t.bid>0&&t.ask>=t.bid?(t.ask-t.bid)/mid:.02;
      const quoteVol=t.price*Math.max(0,t.vol);
      if(quoteVol<25000||spread>.012)continue;
      const liq=clamp((Math.log10(quoteVol+1)-4.4)/3.4,0,1);
      const motion=clamp(Math.abs(t.chg)/.12,0,1);
      const spreadQ=clamp(1-spread/.012,0,1);
      const positive=t.chg>0?0.08:0;
      const pre=.55*liq+.22*motion+.23*spreadQ+positive;
      rows.push({...t,quoteVol,spread,pre});
    }
    return rows.sort((a,b)=>b.pre-a.pre).slice(0,DETAIL_COUNT);
  }

  function candleMetrics(raw){
    const c=(raw||[]).map(x=>({t:+x[0],l:+x[1],h:+x[2],o:+x[3],c:+x[4],v:+x[5]})).sort((a,b)=>a.t-b.t);
    if(c.length<20)return null;
    const last=c.at(-1),close=c.map(x=>x.c);
    const trend5=(last.c-close[Math.max(0,close.length-6)])/close[Math.max(0,close.length-6)];
    const trend15=(last.c-close[Math.max(0,close.length-16)])/close[Math.max(0,close.length-16)];
    let gains=0,losses=0;for(let i=close.length-14;i<close.length;i++){const d=close[i]-close[i-1];if(d>=0)gains+=d;else losses-=d}
    const rsi=losses===0?100:100-100/(1+(gains/14)/(losses/14));
    let tr=0;for(let i=c.length-14;i<c.length;i++)tr+=Math.max(c[i].h-c[i].l,Math.abs(c[i].h-c[i-1].c),Math.abs(c[i].l-c[i-1].c));
    const atr=(tr/14)/last.c;
    return{last:last.c,trend5,trend15,rsi,atr};
  }

  function bookMetrics(j){
    const bids=(j.bids||[]).slice(0,80).map(x=>({p:+x[0],q:+x[1]})).filter(x=>x.p>0&&x.q>0);
    const asks=(j.asks||[]).slice(0,80).map(x=>({p:+x[0],q:+x[1]})).filter(x=>x.p>0&&x.q>0);
    if(!bids.length||!asks.length)return null;
    const bid=bids[0].p,ask=asks[0].p,mid=(bid+ask)/2,spread=(ask-bid)/mid;
    const bn=bids.slice(0,35).map(x=>x.p*x.q),an=asks.slice(0,35).map(x=>x.p*x.q);
    const bd=bn.reduce((s,x)=>s+x,0),ad=an.reduce((s,x)=>s+x,0),imb=(bd-ad)/(bd+ad||1);
    const top5=(bn.slice(0,5).reduce((s,x)=>s+x,0)+an.slice(0,5).reduce((s,x)=>s+x,0))/(bd+ad||1);
    const medB=median(bn),medA=median(an);
    const support=bids.filter(x=>x.p>=mid*.985).sort((a,b)=>(b.p*b.q)-(a.p*a.q))[0]||bids[0];
    const resistance=asks.filter(x=>x.p<=mid*1.015).sort((a,b)=>(b.p*b.q)-(a.p*a.q))[0]||asks[0];
    const supportStrength=medB?(support.p*support.q)/medB:1,resistanceStrength=medA?(resistance.p*resistance.q)/medA:1;
    return{bids,asks,bid,ask,mid,spread,imb,top5,depth:bd+ad,support:support.p,resistance:resistance.p,supportStrength,resistanceStrength};
  }

  function fillBuy(asks,bank){
    const quoteBudget=bank/(1+FEE);let rem=quoteBudget,cost=0,qty=0;
    for(const l of asks){const cap=l.p*l.q,t=Math.min(rem,cap);if(t<=0)break;qty+=t/l.p;cost+=t;rem-=t;if(rem<=1e-7)break}
    if(rem>Math.max(.01,quoteBudget*.001)||qty<=0)return null;
    const avgEntry=cost/qty,buyFee=cost*FEE;
    return{qty,avgEntry,buyFee,total:cost+buyFee};
  }

  function bankPlan(bank,bm,target,stop){
    const f=fillBuy(bm.asks,bank);if(!f)return{bank,valid:false,net:-999,rr:-99};
    const slip=Math.max(0,(f.avgEntry-bm.ask)/bm.ask),exitDrag=Math.max(bm.spread/2,slip*.7),exit=target*(1-exitDrag),gross=f.qty*exit,sellFee=gross*FEE,net=gross-sellFee-bank;
    const stopExit=stop*(1-exitDrag),stopNet=f.qty*stopExit*(1-FEE),loss=Math.max(0,bank-stopNet),rr=loss>0?net/loss:0,be=f.avgEntry*(1+FEE)/(1-FEE);
    return{bank,valid:true,net,loss,rr,qty:f.qty,entry:f.avgEntry,slip,breakEven:be};
  }

  async function analyzeCandidate(t){
    const id=encodeURIComponent(t.id);
    const [book,candles]=await Promise.all([
      jfetch(`${API}/products/${id}/book?level=2`),
      jfetch(`${API}/products/${id}/candles?granularity=60`)
    ]);
    const bm=bookMetrics(book),cm=candleMetrics(candles);if(!bm||!cm)return null;
    const rsiBias=clamp((cm.rsi-50)/35,-1,1),dayBias=clamp(t.chg/.10,-1,1);
    const direction=clamp(.40*bm.imb+.23*clamp(cm.trend5/.01,-1,1)+.17*clamp(cm.trend15/.02,-1,1)+.12*rsiBias+.08*dayBias,-1,1);
    const hollow=bm.top5<.12?.55:bm.top5<.22?.75:bm.top5<.35?.9:1;
    const spreadQ=clamp(1-bm.spread/.008,0,1),depthQ=clamp((Math.log10(bm.depth+1)-3.8)/3,0,1);
    const overheat=cm.rsi>78?.75:cm.rsi<22?.82:1;
    const quality=clamp(.34*hollow+.26*spreadQ+.22*depthQ+.18*clamp(Math.log10(t.quoteVol+1)/8,0,1),0,1)*overheat;
    const baseMove=Math.max(.005,cm.atr*Math.sqrt(12)*.70,Math.abs(cm.trend15)*.65);
    const move=clamp(baseMove*(.62+Math.abs(direction)*.95)*(.8+quality*.4),.003,.06);
    let target=bm.mid*(1+move);
    if(bm.resistance>bm.mid*1.0015&&bm.resistance<target&&bm.resistanceStrength>=2.2)target=bm.resistance;
    const downside=Math.max(cm.atr*1.8,.005);
    let stop=bm.mid*(1-downside);
    if(bm.support<bm.mid&&bm.support>stop&&bm.supportStrength>=2.2)stop=bm.support*(1-.0015);
    const p50=bankPlan(50,bm,target,stop),p100=bankPlan(100,bm,target,stop);
    const bullish=direction>0.12;
    const strength=clamp(Math.abs(direction)*100*quality,0,100);
    function rank(plan){
      if(!plan.valid||!bullish)return 0;
      const edge=clamp(plan.net/(plan.bank*.02),-1,1),rr=clamp(plan.rr/2,0,1);
      return Math.round(clamp(strength*.56+quality*100*.20+Math.max(0,edge)*100*.14+rr*100*.10-(plan.net<=0?28:0),0,100));
    }
    return{id:t.id,price:bm.mid,direction,quality,strength,target,stop,move:(target/bm.mid)-1,spread:bm.spread,imb:bm.imb,atr:cm.atr,rsi:cm.rsi,trend5:cm.trend5,trend15:cm.trend15,quoteVol:t.quoteVol,p50,p100,score50:rank(p50),score100:rank(p100)};
  }

  function recommendation(best50,best100){
    const a=best50,b=best100;
    const good50=a&&a.p50.net>0&&a.score50>=58&&a.p50.rr>=1;
    const good100=b&&b.p100.net>0&&b.score100>=58&&b.p100.rr>=1;
    if(!good50&&!good100)return{amount:'SKIP FOR NOW',text:'No scanned pair currently clears our conservative fee, liquidity and risk checks.'};
    if(good100&&b.score100>=74&&b.p100.rr>=1.25)return{amount:'$100',text:`${b.id} has the strongest current $100 setup after estimated costs.`};
    const pick=good50?a:b;return{amount:'$50',text:`${pick.id} has a usable setup, but $50 keeps risk smaller while the forecast proves itself.`};
  }

  function renderResults(rows){
    const r50=[...rows].sort((a,b)=>b.score50-a.score50||b.p50.net-a.p50.net),r100=[...rows].sort((a,b)=>b.score100-a.score100||b.p100.net-a.p100.net),best50=r50[0],best100=r100[0],rec=recommendation(best50,best100);
    if($s('best50'))$s('best50').textContent=best50?`${best50.id} • score ${best50.score50}/100 • ${money(best50.p50.net)} at target`:'No candidate';
    if($s('best100'))$s('best100').textContent=best100?`${best100.id} • score ${best100.score100}/100 • ${money(best100.p100.net)} at target`:'No candidate';
    if($s('suggestedAmount'))$s('suggestedAmount').textContent=rec.amount;
    if($s('suggestedWhy'))$s('suggestedWhy').textContent=rec.text;
    const combined=[...rows].sort((a,b)=>Math.max(b.score50,b.score100)-Math.max(a.score50,a.score100)).slice(0,6);
    if($s('scanList'))$s('scanList').innerHTML=combined.map((x,i)=>{
      const up=x.direction>0.12,action=x.p50.net>0&&x.score50>=58?'WATCH / POSSIBLE BUY':'WAIT';
      return `<div class="scanrow" data-pair="${x.id}"><b>${i+1}. ${x.id}</b> — ${action}<br><span class="small">Next move: ${up?'UP':'unclear/down'} • target ${px(x.target)} • score $50 ${x.score50}/100 / $100 ${x.score100}/100 • est. net $50 ${money(x.p50.net)} / $100 ${money(x.p100.net)}</span><br><button class="usepair" data-pair="${x.id}">USE ${x.id}</button></div>`;
    }).join('');
    document.querySelectorAll('.usepair').forEach(btn=>btn.onclick=()=>usePair(btn.dataset.pair));
    if($s('scanTime'))$s('scanTime').textContent=`Last full scan: ${new Date().toLocaleTimeString()}`;
    setStatus(rec.amount==='SKIP FOR NOW'?'NO CLEAN PROFIT SETUP RIGHT NOW':`BEST RIGHT NOW: ${rec.amount==='$100'?best100.id:best50.id}`,'good');
  }

  function usePair(pair){
    const p=$s('pair');if(p)p.value=pair;
    const s=$s('start');if(s)s.click();
    const note=$s('scanPicked');if(note)note.textContent=`Loaded ${pair} into the live forecast below.`;
  }

  async function detailedScan(force=false){
    if(scanning)return;if(!force&&Date.now()-lastDetailed<45000)return;
    scanning=true;lastDetailed=Date.now();
    try{
      const cands=preCandidates();
      if(!cands.length){setStatus(`COLLECTING LIVE PRICES — ${tickers.size}/${products.length} pairs heard from`,'warn');return}
      setStatus(`CHECKING ${cands.length} BEST CANDIDATES IN DETAIL…`,'warn');
      const out=[];
      for(let i=0;i<cands.length;i++){
        if($s('scanProgress'))$s('scanProgress').textContent=`Detailed check ${i+1}/${cands.length}: ${cands[i].id}`;
        try{const x=await analyzeCandidate(cands[i]);if(x)out.push(x)}catch{}
        await sleep(275);
      }
      if(!out.length){setStatus('SCAN COULD NOT GET DETAILED MARKET DATA','bad');return}
      renderResults(out);
      if($s('scanProgress'))$s('scanProgress').textContent=`Scanned ${products.length} live USD pairs; deeply checked ${out.length} finalists.`;
    }finally{scanning=false}
  }

  async function launchScan(){
    try{
      setStatus('FRESH STARTUP SCAN — LOADING EVERY USD PAIR…','warn');
      await loadProducts();openTickerSocket();
      for(let i=0;i<6;i++){
        await sleep(1000);
        if($s('scanProgress'))$s('scanProgress').textContent=`Live prices received: ${tickers.size}/${products.length}`;
        if(tickers.size>=Math.min(products.length,80))break;
      }
      await detailedScan(true);
      clearInterval(refreshTimer);refreshTimer=setInterval(()=>detailedScan(false),60000);
    }catch(e){setStatus(`SCANNER ERROR — ${e.message}`,'bad')}
  }

  const rescan=$s('rescanAll');if(rescan)rescan.onclick=()=>detailedScan(true);
  window.addEventListener('beforeunload',closeSocket);
  launchScan();
})();
