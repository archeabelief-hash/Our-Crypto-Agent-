(function(){
  if (document.getElementById('magician-overlay')) return;

  const API = 'https://api.exchange.coinbase.com/products';
  const state = { memory: [], latest: null };

  const style = document.createElement('style');
  style.textContent = [
    '#magician-overlay{position:fixed;top:80px;right:20px;width:310px;background:#0b111c;border:1px solid #24324f;border-radius:12px;color:#eef5ff;font-family:system-ui;z-index:999999;box-shadow:0 10px 40px rgba(0,0,0,0.6);overflow:hidden}',
    '#magician-header{padding:10px;font-weight:800;border-bottom:1px solid #24324f;display:flex;justify-content:space-between}',
    '.magician-row{padding:8px 10px;border-bottom:1px solid rgba(255,255,255,0.05);display:flex;justify-content:space-between;gap:10px}',
    '.magician-label{color:#8fa1bf}',
    '.magician-good{color:#18e59a;font-weight:800}',
    '.magician-bad{color:#ff5f73;font-weight:800}',
    '.magician-warn{color:#ffd166;font-weight:800}',
    '.magician-btn{width:100%;padding:8px;background:#111c30;border:1px solid #4f8cff;color:#eef5ff;border-radius:8px;cursor:pointer;font-weight:800}',
    '#magician-small{padding:8px 10px;color:#c8d3e6;font-size:11px;line-height:1.35}'
  ].join('\n');
  document.head.appendChild(style);

  const box = document.createElement('div');
  box.id = 'magician-overlay';
  box.innerHTML = '<div id="magician-header"><span>Magician Overlay</span><span id="magician-live" class="magician-warn">loading</span></div><div class="magician-row"><span class="magician-label">Pair</span><span id="pair">-</span></div><div class="magician-row"><span class="magician-label">Market State</span><span id="state" class="magician-warn">WAIT</span></div><div class="magician-row"><span class="magician-label">Bid</span><span id="bid">-</span></div><div class="magician-row"><span class="magician-label">Ask</span><span id="ask">-</span></div><div class="magician-row"><span class="magician-label">Spread</span><span id="spread">-</span></div><div class="magician-row"><span class="magician-label">Imbalance</span><span id="imb">-</span></div><div class="magician-row"><span class="magician-label">Momentum</span><span id="mom">-</span></div><div id="magician-small">Reading public order book...</div><div class="magician-row"><button id="copyTrade" class="magician-btn">Copy Market Note</button></div>';
  document.body.appendChild(box);

  function avg(a){ return a.length ? a.reduce((x,y)=>x+y,0)/a.length : 0; }
  function fmt(n){ return Number.isFinite(n) ? n.toLocaleString(undefined,{maximumFractionDigits: Math.abs(n)>=1 ? 6 : 8}) : '-'; }
  function depth(levels,n){ return levels.slice(0,n).reduce((s,x)=>s+x.p*x.q,0); }

  function pairFromPage(){
    const hay = (location.href + ' ' + document.title + ' ' + document.body.innerText.slice(0,1500)).toUpperCase();
    const pairs = ['BTC-USD','ETH-USD','SOL-USD','XLM-USD','DOGE-USD','XRP-USD','ADA-USD','AVAX-USD','LINK-USD','LTC-USD','ELA-USD'];
    for (const p of pairs){
      if (hay.includes(p) || hay.includes(p.replace('-','')) || hay.includes(p.replace('-','/'))) return p;
    }
    return 'BTC-USD';
  }

  async function readBook(pair){
    const res = await fetch(API + '/' + pair + '/book?level=2', {cache:'no-store'});
    if (!res.ok) throw new Error('book unavailable ' + res.status);
    const data = await res.json();
    return {
      bids: (data.bids || []).slice(0,60).map(x=>({p:+x[0],q:+x[1]})),
      asks: (data.asks || []).slice(0,60).map(x=>({p:+x[0],q:+x[1]}))
    };
  }

  function analyze(pair, bids, asks){
    const bid = bids[0] ? bids[0].p : 0;
    const ask = asks[0] ? asks[0].p : 0;
    const spread = ask ? ((ask - bid) / ask) * 100 : 0;
    const bd = depth(bids,35);
    const ad = depth(asks,35);
    const imbalance = bd + ad ? ((bd - ad) / (bd + ad)) * 100 : 0;
    state.memory.push({t:Date.now(), imbalance, spread});
    state.memory = state.memory.filter(x=>Date.now()-x.t<90000).slice(-60);
    const momentum = imbalance - avg(state.memory.map(x=>x.imbalance));
    const spreadAvg = avg(state.memory.map(x=>x.spread)) || spread || 0.0001;
    const spreadSpike = spread / Math.max(0.0001, spreadAvg);
    let marketState = 'WATCH';
    if (spreadSpike >= 2 || momentum < -3) marketState = 'AVOID';
    if (imbalance > 12 && momentum > 3 && spreadSpike < 1.5) marketState = 'STRONG WATCH';
    return {pair,bid,ask,spread,imbalance,momentum,spreadSpike,marketState};
  }

  function render(s){
    state.latest = s;
    document.getElementById('magician-live').textContent = 'live';
    document.getElementById('magician-live').className = 'magician-good';
    document.getElementById('pair').textContent = s.pair;
    const st = document.getElementById('state');
    st.textContent = s.marketState;
    st.className = s.marketState === 'STRONG WATCH' ? 'magician-good' : s.marketState === 'AVOID' ? 'magician-bad' : 'magician-warn';
    document.getElementById('bid').textContent = fmt(s.bid);
    document.getElementById('ask').textContent = fmt(s.ask);
    document.getElementById('spread').textContent = s.spread.toFixed(4) + '%';
    document.getElementById('imb').textContent = s.imbalance.toFixed(1);
    document.getElementById('mom').textContent = s.momentum.toFixed(1);
    document.getElementById('magician-small').textContent = 'Spread spike ' + s.spreadSpike.toFixed(2) + 'x | Public Coinbase book only | Manual review required';
  }

  async function loop(){
    try {
      const pair = pairFromPage();
      const book = await readBook(pair);
      render(analyze(pair, book.bids, book.asks));
    } catch(e) {
      document.getElementById('magician-live').textContent = 'error';
      document.getElementById('magician-live').className = 'magician-bad';
      document.getElementById('magician-small').textContent = e.message || String(e);
    }
    setTimeout(loop, 3000);
  }

  document.getElementById('copyTrade').onclick = function(){
    const s = state.latest;
    if (!s) return;
    const txt = ['COINBASE MAGICIAN MARKET NOTE','Pair: ' + s.pair,'State: ' + s.marketState,'Bid: ' + fmt(s.bid),'Ask: ' + fmt(s.ask),'Spread: ' + s.spread.toFixed(4) + '%','Imbalance: ' + s.imbalance.toFixed(1),'Momentum: ' + s.momentum.toFixed(1),'Manual review required.'].join('\n');
    navigator.clipboard.writeText(txt);
  };

  loop();
})();
