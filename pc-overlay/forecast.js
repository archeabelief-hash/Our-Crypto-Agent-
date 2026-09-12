(()=>{
  const baseRender=render;
  const hist=[];
  const pending=[];
  const stats={m2:{hit:0,miss:0},m5:{hit:0,miss:0},m15:{hit:0,miss:0}};
  const BASE_MOVE={m2:.0015,m5:.0035,m15:.0075};
  const PATTERN_KEY='twistedForecastPatternsV1';
  const htf={oneHour:null,twoHour:null,last:0};
  let lastSample=0,lastPrediction=0;
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  const avg=a=>a.length?a.reduce((s,x)=>s+x,0)/a.length:0;
  const std=a=>{if(a.length<2)return 0;const m=avg(a);return Math.sqrt(avg(a.map(x=>(x-m)*(x-m))))};
  const strengthWord=n=>n<20?'VERY WEAK':n<35?'WEAK':n<55?'FAIR':n<72?'STRONG':'VERY STRONG';
  const riskWord=n=>n<.25?'LOW':n<.5?'MEDIUM':'HIGH';
  const patternRead=()=>{try{return JSON.parse(localStorage.getItem(PATTERN_KEY)||'{}')}catch{return{}}};
  const patternWrite=o=>{try{localStorage.setItem(PATTERN_KEY,JSON.stringify(o))}catch{}};

  async function publicCandles(product,granularity,seconds,lookbackBars){
    const end=Math.floor(Date.now()/1000),start=end-seconds;
    const u=`https://api.coinbase.com/api/v3/brokerage/market/products/${encodeURIComponent(product)}/candles?start=${start}&end=${end}&granularity=${granularity}&limit=120`;
    const r=await fetch(u),j=await r.json(),c=(j.candles||[]).map(x=>({t:+x.start,c:+x.close})).sort((a,b)=>a.t-b.t);
    if(c.length<lookbackBars+1)return null;
    const a=c.at(-1).c,b=c[Math.max(0,c.length-1-lookbackBars)].c;
    return b>0?(a-b)/b:null;
  }
  async function refreshHTF(){
    const now=Date.now();if(now-htf.last<60000)return;htf.last=now;
    const product=($('pair')?.value||'BTC-USD').trim().toUpperCase();
    try{const [h1,h2]=await Promise.all([publicCandles(product,'FIVE_MINUTE',60*60*8,12),publicCandles(product,'FIFTEEN_MINUTE',60*60*24,8)]);if(h1!=null)htf.oneHour=h1;if(h2!=null)htf.twoHour=h2}catch{}
  }

  function strongest(map,prices){let p=null,q=-1;for(const x of prices){const v=map.get(x)||0;if(v>q){p=x;q=v}}return{price:p,qty:Math.max(0,q)}}
  function snapshot(m){
    const bp=[...bids.keys()].sort((x,y)=>y-x),ap=[...asks.keys()].sort((x,y)=>x-y);
    const mid=m.mid,nearBid=bp.filter(p=>p>=mid*.994),nearAsk=ap.filter(p=>p<=mid*1.006);
    const bd=nearBid.reduce((s,p)=>s+(bids.get(p)||0),0),ad=nearAsk.reduce((s,p)=>s+(asks.get(p)||0),0);
    const imb=(bd-ad)/(bd+ad||1);
    const now=Date.now();
    const recentTrades=trades.filter(t=>now-t.t<=10000),buy=recentTrades.filter(t=>t.buy).reduce((s,t)=>s+t.q,0),sell=recentTrades.filter(t=>!t.buy).reduce((s,t)=>s+t.q,0),flow=(buy-sell)/(buy+sell||1);
    const top5=bp.slice(0,5).reduce((s,p)=>s+(bids.get(p)||0),0)+ap.slice(0,5).reduce((s,p)=>s+(asks.get(p)||0),0);
    const top35=bp.slice(0,35).reduce((s,p)=>s+(bids.get(p)||0),0)+ap.slice(0,35).reduce((s,p)=>s+(asks.get(p)||0),0);
    const support=strongest(bids,bp.filter(p=>p<=mid&&p>=mid*.985).slice(0,120));
    const resistance=strongest(asks,ap.filter(p=>p>=mid&&p<=mid*1.015).slice(0,120));
    let live=0;for(const v of Object.values(venue))if(v&&now-v.t<15000)live++;
    return{t:now,mid,spread:m.spread,imb,flow,top5Share:top5/(top35||1),depth:top35,support:support.price||mid,resistance:resistance.price||mid,supportQty:support.qty,resistanceQty:resistance.qty,live};
  }
  function remember(s){if(s.t-lastSample<900)return;lastSample=s.t;hist.push(s);while(hist.length&&s.t-hist[0].t>120000)hist.shift()}
  function rows(ms,now){return hist.filter(x=>now-x.t<=ms)}
  function returns(rs){const out=[];for(let i=1;i<rs.length;i++)if(rs[i-1].mid>0)out.push((rs[i].mid-rs[i-1].mid)/rs[i-1].mid);return out}
  function persistence(price,key,rs,mid){if(!price||!rs.length)return 0;const tol=mid*.0008;return rs.filter(x=>Math.abs(x[key]-price)<=tol).length/rs.length}
  function hollowFactor(share){if(share<.12)return .55;if(share<.22)return .75;if(share<.35)return .90;return 1}
  function momentumFactor(aligned){if(aligned>8)return 1.18;if(aligned>3)return 1.08;if(aligned<-8)return .72;if(aligned<-3)return .88;return 1}
  function spreadFactor(r){if(r>1.5)return .65;if(r>1.2)return .82;return 1}
  function wallFactor(p){if(p>.70)return 1.12;if(p>.40)return 1.04;if(p<.15)return .82;return 1}
  function directionScores(s,imbMom){
    const mom=clamp(imbMom/20,-1,1),trend10=clamp((candle.trend||0)/.003,-1,1),h1=clamp((htf.oneHour||0)/.01,-1,1),h2=clamp((htf.twoHour||0)/.018,-1,1);
    return{
      m2:clamp(.40*s.imb+.30*s.flow+.20*mom+.10*trend10,-1,1),
      m5:clamp(.28*s.imb+.18*s.flow+.19*mom+.20*trend10+.15*h1,-1,1),
      m15:clamp(.12*s.imb+.08*s.flow+.12*mom+.18*trend10+.28*h1+.22*h2,-1,1)
    };
  }
  function horizon(s,score,key,mins,factors){
    const dynamic=Math.max(candle.atrPct||.0015,.0008)*Math.sqrt(mins)*.72,base=Math.max(BASE_MOVE[key],dynamic),conv=.35+Math.abs(score)*.65;
    const raw=base*factors*conv,cap=base*2.2,move=clamp(raw,base*.20,cap),dir=Math.abs(score)<.14?0:(score>0?1:-1),projected=s.mid*(1+dir*move);
    let first=projected;if(dir>0&&s.resistance>s.mid&&s.resistance<projected)first=s.resistance;if(dir<0&&s.support<s.mid&&s.support>projected)first=s.support;
    return{dir,score,move,projected,first};
  }
  function horizonText(h,mins,s){if(h.dir===0){const r=s.mid*Math.max(candle.atrPct||.0015,.001)*Math.sqrt(mins)*.6;return`Likely sideways/choppy around ${fmt(s.mid-r)} – ${fmt(s.mid+r)}`}return h.dir>0?`Likely HIGHER first, toward ${fmt(h.first)}`:`Likely LOWER first, toward ${fmt(h.first)}`}
  function fingerprint(s,score,imbMom,spreadSpike,volRatio,hollow){
    const dir=score>=0?'UP':'DOWN',sc=Math.abs(score)*100<25?'S0':Math.abs(score)*100<45?'S1':Math.abs(score)*100<65?'S2':'S3',aligned=imbMom*(score>=0?1:-1),mo=aligned<-3?'MNEG':aligned<3?'MFLAT':aligned<12?'MPOS':'MSTR',sp=spreadSpike<1.15?'SP0':spreadSpike<1.5?'SP1':spreadSpike<2?'SP2':'SP3',vo=volRatio<.8?'VLOW':volRatio<1.4?'VNORM':volRatio<1.8?'VHIGH':'VCHAOS',ho=hollow<.75?'HLOW':hollow<.9?'HMID':'HGOOD';return`${$('pair')?.value||'PAIR'}|${dir}|${sc}|${mo}|${sp}|${vo}|${ho}`;
  }
  function patternState(key){const p=patternRead()[key];if(!p||p.samples<5)return{status:'LEARNING',samples:p?.samples||0,mult:1};const wr=p.hits/p.samples;return{status:wr>=.62?'FAVORABLE':wr<=.45?'UNFAVORABLE':'NEUTRAL',samples:p.samples,winrate:wr,mult:wr>=.62?1.08:wr<=.45?.82:1}}
  function recordPattern(key,hit){const all=patternRead(),p=all[key]||{samples:0,hits:0};p.samples++;if(hit)p.hits++;all[key]=p;patternWrite(all)}
  function evaluate(now,mid){for(let i=pending.length-1;i>=0;i--){const p=pending[i];if(now<p.due)continue;const move=(mid-p.start)/p.start,edge=p.dir*move,threshold=p.threshold;if(Math.abs(move)>=threshold){const hit=edge>0;if(hit)stats[p.key].hit++;else stats[p.key].miss++;recordPattern(p.pattern,hit)}pending.splice(i,1)}}
  function addPredictions(now,s,hs,patternKey){if(now-lastPrediction<60000)return;lastPrediction=now;const threshold=Math.max(.0005,(candle.atrPct||.0015)*.25);for(const [key,mins] of [['m2',2],['m5',5],['m15',15]]){const h=hs[key];if(h.dir)pending.push({key,dir:h.dir,start:s.mid,due:now+mins*60000,threshold,pattern:patternKey})}}
  function selfCheck(pattern){const text=[];for(const [label,key] of [['2m','m2'],['5m','m5'],['15m','m15']]){const x=stats[key],n=x.hit+x.miss;if(n)text.push(`${label} ${x.hit}/${n} right`)}if(pattern.samples>=5)text.push(`similar setups ${Math.round(pattern.winrate*100)}% (${pattern.samples})`);return text.length?text.join(' • '):'Learning — no forecast has matured yet'}

  render=function(m){
    refreshHTF();
    const s=snapshot(m);remember(s);const now=s.t,r60=rows(60000,now),r15=rows(15000,now),avgImb=avg(r60.map(x=>x.imb))*100,imbMom=s.imb*100-avgImb,avgSpread=avg(r60.map(x=>x.spread))||s.spread,spreadSpike=s.spread/Math.max(avgSpread,1e-9),vol15=std(returns(r15)),vol60=std(returns(r60)),volRatio=vol60>0?clamp(vol15/vol60,.5,2.5):1,supPersist=persistence(s.support,'support',r60,s.mid),resPersist=persistence(s.resistance,'resistance',r60,s.mid),wallPersist=(supPersist+resPersist)/2,hollow=hollowFactor(s.top5Share),avgDepth=avg(r60.map(x=>x.depth))||s.depth,liq=clamp(s.depth/Math.max(avgDepth,1e-9),.75,1.25),scores=directionScores(s,imbMom),dominant=Math.abs(scores.m5)>=.14?(scores.m5>0?1:-1):0,alignedMom=imbMom*(dominant||1),imbalanceStrength=clamp(.65+Math.min(1.8,Math.abs(s.imb*100)/35),.65,2.45),mf=momentumFactor(alignedMom),sf=spreadFactor(spreadSpike),wf=hist.length<8?1:wallFactor(wallPersist),vf=volRatio>1.75?.72:volRatio>1.35?.86:volRatio<.7?.92:1,scout=s.live>=3?1:s.live===2?.96:.90,factors=imbalanceStrength*liq*mf*sf*hollow*wf*vf*scout;
    const hs={m2:horizon(s,scores.m2,'m2',2,factors),m5:horizon(s,scores.m5,'m5',5,factors),m15:horizon(s,scores.m15,'m15',15,factors)},signFlips=r60.slice(1).reduce((n,x,i)=>n+((x.imb>0)!==(r60[i].imb>0)?1:0),0),fakeRisk=clamp((wallPersist<.20?.28:0)+(spreadSpike>1.5?.25:0)+(s.top5Share<.22?.22:0)+(signFlips>=3?.20:0)+(Math.abs(imbMom)>18?.10:0),0,1),historyQuality=clamp(r60.length/45,0,1),quality=clamp(historyQuality*.45+hollow*.20+sf*.15+(1-fakeRisk)*.20,0,1),pKey=fingerprint(s,scores.m5,imbMom,spreadSpike,volRatio,hollow),pattern=patternState(pKey),strength=Math.round(clamp(Math.abs(scores.m5)*100*quality*pattern.mult,0,100));
    let regime='NORMAL';if(spreadSpike>=2)regime='UNRELIABLE — PRICE GAP JUST SPIKED';else if(hollow<.75)regime='UNRELIABLE — ORDER BOOK IS HOLLOW';else if(fakeRisk>=.55)regime='UNRELIABLE — BIG WALLS MAY BE FAKE';else if(Math.abs(imbMom)<2&&strength<35)regime='CHOPPY — NO CLEAN DIRECTION';else if(volRatio>=1.8&&quality<.72)regime='UNSTABLE — PRICE IS MOVING TOO WILDLY';
    let path;if(hs.m5.dir<0)path=`Most likely path: move DOWN first → test the floor near ${fmt(s.support)}. If that floor holds, watch for a bounce back toward ${fmt(Math.min(s.resistance,s.mid+Math.max(s.mid*(candle.atrPct||.0015),(s.mid-s.support)*.8)))}.`;else if(hs.m5.dir>0)path=`Most likely path: move UP first → test the ceiling near ${fmt(s.resistance)}. If that ceiling rejects price, watch for a pullback toward ${fmt(Math.max(s.support,s.mid-Math.max(s.mid*(candle.atrPct||.0015),(s.resistance-s.mid)*.8)))}.`;else path=`Most likely path: chop between the floor near ${fmt(s.support)} and ceiling near ${fmt(s.resistance)} until one side breaks.`;
    let status='FORECAST WARMING UP';if(r60.length>=12)status=regime!=='NORMAL'?regime:hs.m5.dir>0?'FORECAST: UPWARD MOVE MORE LIKELY':hs.m5.dir<0?'FORECAST: DOWNWARD MOVE MORE LIKELY':'FORECAST: SIDEWAYS / RANGE MORE LIKELY';
    evaluate(now,s.mid);addPredictions(now,s,hs,pKey);baseRender(m);
    if($('forecastStatus'))$('forecastStatus').textContent=status;if($('forecastPath'))$('forecastPath').textContent=path;if($('forecast2'))$('forecast2').textContent=horizonText(hs.m2,2,s);if($('forecast5'))$('forecast5').textContent=horizonText(hs.m5,5,s);if($('forecast15'))$('forecast15').textContent=horizonText(hs.m15,15,s);if($('forecastStrength'))$('forecastStrength').textContent=`${strength}/100 — ${strengthWord(strength)}`;if($('forecastQuality'))$('forecastQuality').textContent=r60.length<12?'LEARNING LIVE BEHAVIOR':quality>.72?'GOOD':quality>.52?'FAIR':'LOW';if($('fakeRisk'))$('fakeRisk').textContent=`${riskWord(fakeRisk)}${fakeRisk>=.5?' — do not trust big walls yet':''}`;if($('forecastCheck'))$('forecastCheck').textContent=selfCheck(pattern);if($('htfRead'))$('htfRead').textContent=htf.oneHour==null?'Warming up':`${htf.oneHour>=0?'1-hour trend up':'1-hour trend down'} • ${htf.twoHour==null?'2-hour loading':htf.twoHour>=0?'2-hour trend up':'2-hour trend down'}`;if($('conf'))$('conf').textContent=`${m.conf}/100 — ${m.conf<25?'WEAK':m.conf<50?'LOW':m.conf<70?'FAIR':'STRONG'}`;
  };
})();