(function(){
  if (document.getElementById('magician-overlay')) return;

  const API = 'https://api.exchange.coinbase.com/products';
  const state = { memory: [], latest: null };

  const style = document.createElement('style');
  style.textContent = [
    '#magician-overlay{position:fixed;top:80px;right:20px;width:330px;background:#0b111c;border:1px solid #24324f;border-radius:12px;color:#eef5ff;font-family:system-ui;z-index:999999;box-shadow:0 10px 40px rgba(0,0,0,0.6);overflow:hidden}',
    '#magician-header{padding:10px;font-weight:800;border-bottom:1px solid #24324f;display:flex;justify-content:space-between}',
    '.magician-row{padding:8px 10px;border-bottom:1px solid rgba(255,255,255,0.05);display:flex;justify-content:space-between;gap:10px}',
    '.magician-label{color:#8fa1bf}',
    '.magician-good{color:#18e59a;font-weight:800}',
    '.magician-bad{color:#ff5f73;font-weight:800}',
    '.magician-warn{color:#ffd166;font-weight:800}',
    '.magician-btn{width:100%;padding:8px;background:#111c30;border:1px solid #4f8cff;color:#eef5ff;border-radius:8px;cursor:pointer;font-weight:800}',
    '#magician-small{padding:8px 10px;color:#c8d3e6;font-size:11px;line-height:1.35}',
    '.magician-flash-good{animation:magicianPulseGood 1.2s ease-in-out infinite}',
    '.magician-flash-bad{animation:magicianPulseBad 1.2s ease-in-out infinite}',
    '@keyframes magicianPulseGood{0%{border-color:#24324f}50%{border-color:#18e59a}100%{border-color:#24324f}}',
    '@keyframes magicianPulseBad{0%{border-color:#24324f}50%{border-color:#ff5f73}100%{border-color:#24324f}}'
  ].join('\n');
  document.head.appendChild(style);

  const box = document.createElement('div');
  box.id = 'magician-overlay';
  box.innerHTML = '<div id="magician-header"><span>Magician Overlay</span><span id="magician-live" class="magician-warn">loading</span></div><div class="magician-row"><span class="magician-label">Pair</span><span id="pair">-</span></div><div class="magician-row"><span class="magician-label">Market State</span><span id="state" class="magician-warn">WAIT</span></div><div class="magician-row"><span class="magician-label">Entry Window</span><span id="entryWindow" class="magician-warn">WAIT</span></div><div class="magician-row"><span class="magician-label">Sniper Timing</span><span id="sniperTiming">-</span></div><div class="magician-row"><span class="magician-label">Confidence</span><span id="confidence">-</span></div><div class="magician-row"><span class="magician-label">Bid</span><span id="bid">-</span></div><div class="magician-row"><span class="magician-label">Ask</span><span id="ask">-</span></div><div class="magician-row"><span class="magician-label">Spread</span><span id="spread">-</span></div><div class="magician-row"><span class="magician-label">Imbalance</span><span id="imb">-</span></div><div class="magician-row"><span class="magician-label">Momentum</span><span id="mom">-</span></div><div id="magician-small">Reading public order book...</div><div class="magician-row"><button id="copyTrade" class="magician-btn">Copy Market Note</button></div>';
  document.body.appendChild(box);

  function avg(a){ return a.length ? a.reduce((x,y)=>x+y,0)/a.length : 0; }
  function fmt(n){ return Number.isFinite(n) ? n.toLocaleString(undefined,{maximumFractionDigits: Math.abs(n)>=1 ? 6 : 8}) : '-'; }
  function depth(levels,n){ return levels.slice(0,n).reduce((s,x)=>s+x.p*x.q,0); }
  function clamp(n,a,b){ return Math.max(a, Math.min(b, n)); }

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
    const topBid = depth(bids,5);
    const topAsk = depth(asks,5);
    const hollow = topAsk / Math.max(1, ad);
    const imbalance = bd + ad ? ((bd - ad) / (bd + ad)) * 100 : 0;
    const now = Date.now();
    state.memory.push({t:now, imbalance, spread, mid:(bid+ask)/2});
    state.memory = state.memory.filter(x=>now-x.t<90000).slice(-60);

    const avgImb = avg(state.memory.map(x=>x.imbalance));
    const momentum = imbalance - avgImb;
    const spreadAvg = avg(state.memory.map(x=>x.spread)) || spread || 0.0001;
    const spreadSpike = spread / Math.max(0.0001, spreadAvg);
    const prev = state.memory.length > 1 ? state.memory[state.memory.length - 2] : null;
    const acceleration = prev ? imbalance - prev.imbalance : 0;
    const recent = state.memory.slice(-8);
    const early = recent.slice(0,4);
    const late = recent.slice(4);
    const exhaustion = recent.length >= 8 ? avg(early.map(x=>x.imbalance)) - avg(late.map(x=>x.imbalance)) : 0;
    const pullback = recent.length >= 4 ? recent[recent.length-1].mid < avg(recent.slice(-4).map(x=>x.mid)) && acceleration > -2 : false;

    let confidence = 50;
    confidence += imbalance > 12 ? 12 : imbalance < 0 ? -10 : 0;
    confidence += momentum > 3 ? 14 : momentum < -3 ? -16 : 0;
    confidence += acceleration > 0 ? 8 : -5;
    confidence += spreadSpike < 1.25 ? 8 : -16;
    confidence += hollow >= 0.22 ? 6 : -14;
    confidence += exhaustion > 4 ? -10 : 0;
    confidence = Math.round(clamp(confidence, 0, 100));

    let marketState = 'WATCH';
    if (spreadSpike >= 2 || momentum < -3 || confidence < 38) marketState = 'AVOID';
    if (imbalance > 12 && momentum > 3 && spreadSpike < 1.5 && confidence >= 68) marketState = 'STRONG WATCH';

    let sniperTiming = 'WAIT';
    let entryWindow = 'WAIT';
    if (marketState === 'AVOID') {
      sniperTiming = 'DANGER';
      entryWindow = 'NO WINDOW';
    } else if (confidence >= 74 && acceleration > 0 && spreadSpike < 1.25 && exhaustion <= 4) {
      sniperTiming = 'SNIPER READY';
      entryWindow = pullback ? 'OPTIMAL PULLBACK' : 'EARLY BUILD';
    } else if (confidence >= 62 && momentum > 0 && spreadSpike < 1.5) {
      sniperTiming = 'FORMING';
      entryWindow = 'WATCH CLOSE';
    } else if (exhaustion > 4 || acceleration < -4) {
      sniperTiming = 'EXHAUSTED';
      entryWindow = 'LATE / WAIT';
    }

    return {pair,bid,ask,spread,imbalance,momentum,spreadSpike,marketState,confidence,sniperTiming,entryWindow,acceleration,exhaustion,hollow};
  }

  function render(s){
    state.latest = s;
    box.classList.remove('magician-flash-good','magician-flash-bad');
    if (s.entryWindow === 'OPTIMAL PULLBACK' || s.sniperTiming === 'SNIPER READY') box.classList.add('magician-flash-good');
    if (s.marketState === 'AVOID' || s.sniperTiming === 'DANGER') box.classList.add('magician-flash-bad');

    document.getElementById('magician-live').textContent = 'live';
    document.getElementById('magician-live').className = 'magician-good';
    document.getElementById('pair').textContent = s.pair;
    const st = document.getElementById('state');
    st.textContent = s.marketState;
    st.className = s.marketState === 'STRONG WATCH' ? 'magician-good' : s.marketState === 'AVOID' ? 'magician-bad' : 'magician-warn';
    const ew = document.getElementById('entryWindow');
    ew.textContent = s.entryWindow;
    ew.className = s.entryWindow === 'OPTIMAL PULLBACK' ? 'magician-good' : s.entryWindow.includes('WAIT') || s.entryWindow === 'NO WINDOW' ? 'magician-bad' : 'magician-warn';
    const sn = document.getElementById('sniperTiming');
    sn.textContent = s.sniperTiming;
    sn.className = s.sniperTiming === 'SNIPER READY' ? 'magician-good' : s.sniperTiming === 'DANGER' || s.sniperTiming === 'EXHAUSTED' ? 'magician-bad' : 'magician-warn';
    const cf = document.getElementById('confidence');
    cf.textContent = s.confidence + '%';
    cf.className = s.confidence >= 70 ? 'magician-good' : s.confidence < 45 ? 'magician-bad' : 'magician-warn';
    document.getElementById('bid').textContent = fmt(s.bid);
    document.getElementById('ask').textContent = fmt(s.ask);
    document.getElementById('spread').textContent = s.spread.toFixed(4) + '%';
    document.getElementById('imb').textContent = s.imbalance.toFixed(1);
    document.getElementById('mom').textContent = s.momentum.toFixed(1);
    document.getElementById('magician-small').textContent = 'Accel ' + s.acceleration.toFixed(1) + ' | Exhaust ' + s.exhaustion.toFixed(1) + ' | Hollow ' + s.hollow.toFixed(2) + ' | Manual review only';
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
    const txt = ['COINBASE MAGICIAN MARKET NOTE','Pair: ' + s.pair,'State: ' + s.marketState,'Entry Window: ' + s.entryWindow,'Sniper: ' + s.sniperTiming,'Confidence: ' + s.confidence + '%','Bid: ' + fmt(s.bid),'Ask: ' + fmt(s.ask),'Spread: ' + s.spread.toFixed(4) + '%','Imbalance: ' + s.imbalance.toFixed(1),'Momentum: ' + s.momentum.toFixed(1),'Manual review only. No automatic trading.'].join('\n');
    navigator.clipboard.writeText(txt);
  };

  loop();
})();
