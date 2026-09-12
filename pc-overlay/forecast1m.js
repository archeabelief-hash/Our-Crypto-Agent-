(()=>{
  const priorRender=render;
  const clamp=(n,a,b)=>Math.max(a,Math.min(b,n));
  render=function(m){
    priorRender(m);
    try{
      const bp=[...bids.keys()].sort((a,b)=>b-a),ap=[...asks.keys()].sort((a,b)=>a-b);
      if(!bp.length||!ap.length||!m?.mid)return;
      const mid=m.mid,now=Date.now();
      const nearB=bp.filter(p=>p>=mid*.997).slice(0,30),nearA=ap.filter(p=>p<=mid*1.003).slice(0,30);
      const bd=nearB.reduce((s,p)=>s+(bids.get(p)||0),0),ad=nearA.reduce((s,p)=>s+(asks.get(p)||0),0),imb=(bd-ad)/(bd+ad||1);
      const rt=trades.filter(t=>now-t.t<=5000),buy=rt.filter(t=>t.buy).reduce((s,t)=>s+t.q,0),sell=rt.filter(t=>!t.buy).reduce((s,t)=>s+t.q,0),flow=(buy-sell)/(buy+sell||1);
      const candleBias=clamp((candle.trend||0)/.0025,-1,1),score=clamp(.52*imb+.34*flow+.14*candleBias,-1,1);
      const atr=Math.max(candle.atrPct||.0012,.0006),move=clamp(atr*(.40+Math.abs(score)*.90),.0005,.015),dir=Math.abs(score)<.12?0:(score>0?1:-1),projected=mid*(1+dir*move);
      let target=projected;
      if(dir>0){const r=ap.find(p=>p>mid);if(r&&r<target)target=r}
      if(dir<0){const s=bp.find(p=>p<mid);if(s&&s>target)target=s}
      const el=document.getElementById('forecast2');
      if(el)el.textContent=dir===0?`Likely sideways/choppy around ${fmt(mid*(1-move*.55))} – ${fmt(mid*(1+move*.55))}`:dir>0?`Likely HIGHER first, toward ${fmt(target)} (1m pressure ${(score*100).toFixed(0)})`:`Likely LOWER first, toward ${fmt(target)} (1m pressure ${(score*100).toFixed(0)})`;
    }catch{}
  };
})();