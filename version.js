const APP_VERSION = '2026-04-28-target-optimizer-v1';

(function(){
  const links = document.querySelectorAll('a[href$=".html"]');
  links.forEach(link => {
    if (!link.href.includes('?v=')) {
      link.href = link.href + '?v=' + APP_VERSION;
    }
  });
})();

(function(){
  const path = location.pathname;

  function setText(selector, text){
    const el = document.querySelector(selector);
    if (el) el.textContent = text;
  }

  function relabelLinks(map){
    document.querySelectorAll('a').forEach(a => {
      const raw = a.getAttribute('href') || '';
      Object.keys(map).forEach(key => {
        if (raw.includes(key)) a.textContent = map[key];
      });
    });
  }

  if (path.endsWith('home.html') || path.endsWith('/')) {
    relabelLinks({
      'live-decision-v2.html': 'AI Trade Engine',
      'execution-tracker.html': 'Execution Tracker',
      'agent-trade-tracker-v4.html': 'Legacy Execution Tracker',
      'manual-selected.html': 'Manual Analyzer',
      'liquidity-pnl-engine.html': 'Liquidity PnL',
      'live-wave-test.html': 'Wave Test',
      'agent.html': 'Agent View',
      'install.html': 'Install'
    });
  }

  if (path.endsWith('live-decision-v2.html')) {
    document.title = 'AI Trade Engine';
    setText('h1', 'AI Trade Engine');
    setText('section.card h2', 'Engine Controls');
    relabelLinks({
      'home.html': 'Home',
      'live-decision.html': 'Legacy Scanner',
      'agent-trade-tracker-v2.html': 'Legacy Tracker',
      'manual-selected.html': 'Manual Analyzer'
    });
    const subtitle = document.querySelector('.top .small');
    if (subtitle) subtitle.textContent = 'Behavior-aware trade engine: imbalance momentum, spread-spike halt, hollow-book filter, volatility-adjusted thresholds, maker-first bias, and adaptive safety buffer.';
    document.querySelectorAll('h2').forEach(h => {
      h.textContent = h.textContent
        .replace('Best V2 Decision', 'Best AI Trade Decision')
        .replace('V2 Interval Scanner', 'AI Interval Scanner')
        .replace('V2 Diagnostics', 'Engine Diagnostics');
    });
    document.querySelectorAll('.label').forEach(el => {
      el.textContent = el.textContent.replace('Score V2', 'Engine Score');
    });
  }

  if (path.endsWith('agent-trade-tracker-v4.html') || path.endsWith('execution-tracker.html')) {
    document.title = 'Execution Tracker';
    setText('h1', 'Execution Tracker');
    relabelLinks({
      'home.html': 'Home',
      'live-decision-v2.html': 'AI Trade Engine',
      'liquidity-pnl-engine.html': 'Liquidity PnL',
      'agent-trade-tracker-v3.html': 'Legacy Tracker'
    });
  }
})();

(function(){
  if (!location.pathname.endsWith('execution-tracker.html')) return;

  const STORAGE_KEY = 'executionTrackerActiveV1';
  const MIN_SAMPLES = 4;

  function read(){
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{"open":[],"closed":[],"blocked":[]}'); }
    catch (_) { return { open: [], closed: [], blocked: [] }; }
  }

  function write(state){
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function usd(n){
    return Number.isFinite(n) ? '$' + n.toLocaleString(undefined,{maximumFractionDigits:2}) : '—';
  }

  function fmt(n){
    return Number.isFinite(n) ? n.toLocaleString(undefined,{maximumFractionDigits:Math.abs(n)>=1?6:8}) : '—';
  }

  function ratioForTrade(t){
    const planned = (Number(t.target) - Number(t.entry)) * Number(t.qty || 0);
    if (!Number.isFinite(planned) || planned <= 0) return null;
    const mfe = Number(t.mfe || 0);
    return Math.max(0, mfe / planned);
  }

  function groupedStats(state){
    const groups = {};
    (state.closed || []).forEach(t => {
      if (!t || !t.tf) return;
      const r = ratioForTrade(t);
      if (r === null) return;
      if (!groups[t.tf]) groups[t.tf] = [];
      groups[t.tf].push({ ratio: r, win: Number(t.pnl || 0) >= 0 });
    });
    const out = {};
    Object.keys(groups).forEach(tf => {
      const rows = groups[tf].slice(-50);
      const ratios = rows.map(x => x.ratio).sort((a,b)=>a-b);
      const avg = ratios.reduce((s,x)=>s+x,0) / ratios.length;
      const p50 = ratios[Math.floor(ratios.length * 0.50)] || 0;
      const p70 = ratios[Math.floor(ratios.length * 0.70)] || p50;
      const hitRate = rows.filter(x => x.win).length / rows.length;
      let multiplier = 1;
      if (rows.length >= MIN_SAMPLES) {
        if (hitRate < 0.35) multiplier = Math.max(0.35, Math.min(0.85, p70 * 0.92));
        else if (hitRate < 0.50) multiplier = Math.max(0.50, Math.min(0.95, p70));
        else if (hitRate > 0.70 && avg > 1.15) multiplier = 1.08;
        else multiplier = Math.max(0.65, Math.min(1.05, p70));
      }
      out[tf] = { samples: rows.length, avg, p50, p70, hitRate, multiplier };
    });
    return out;
  }

  function optimizeOpenTargets(){
    const state = read();
    const stats = groupedStats(state);
    let changed = false;
    (state.open || []).forEach(t => {
      const s = stats[t.tf];
      if (!s || s.samples < MIN_SAMPLES) return;
      if (!Number.isFinite(Number(t.originalTarget))) t.originalTarget = Number(t.target);
      const baseMove = Number(t.originalTarget) - Number(t.entry);
      if (!Number.isFinite(baseMove) || baseMove <= 0) return;
      const suggested = Number(t.entry) + baseMove * s.multiplier;
      if (suggested > Number(t.entry) && suggested < Number(t.target) * 1.2) {
        t.target = suggested;
        t.adaptiveTarget = suggested;
        t.targetMultiplier = s.multiplier;
        t.targetOptimizerSamples = s.samples;
        changed = true;
      }
    });
    if (changed) write(state);
    return { state, stats };
  }

  function panelHtml(stats){
    const keys = Object.keys(stats);
    if (!keys.length) return '<div class="small">Waiting for closed trades with MFE data. Need at least 4 samples per timeframe before auto-adjusting targets.</div>';
    return keys.map(tf => {
      const s = stats[tf];
      const ready = s.samples >= MIN_SAMPLES;
      return `<div class="order ${ready?'open':'block'}"><b>${tf}</b> <span class="pill ${ready?'pill-good':'pill-warn'}">${ready?'ACTIVE':'LEARNING'}</span><br>Samples ${s.samples} | Hit Rate ${(s.hitRate*100).toFixed(1)}%<br>Avg MFE ${(s.avg*100).toFixed(1)}% of target | P70 ${(s.p70*100).toFixed(1)}%<br>Suggested Target Multiplier <b>${s.multiplier.toFixed(2)}x</b><br>${ready?'Open trades in this TF are adjusted toward this target.':'Needs more closed trades.'}</div>`;
    }).join('');
  }

  function renderOptimizer(){
    const result = optimizeOpenTargets();
    let card = document.getElementById('targetOptimizerCard');
    const summaryCard = Array.from(document.querySelectorAll('section.card')).find(s => (s.querySelector('h2')||{}).textContent === 'Summary');
    if (!card && summaryCard) {
      card = document.createElement('section');
      card.className = 'card';
      card.id = 'targetOptimizerCard';
      card.innerHTML = '<h2>Adaptive Target Optimizer</h2><div class="small">Uses closed-trade MFE and hit-rate data to tighten unrealistic targets by timeframe. This improves paper evaluation before live execution.</div><div id="targetOptimizerRows" class="orders" style="margin-top:10px"></div>';
      summaryCard.insertAdjacentElement('afterend', card);
    }
    const rows = document.getElementById('targetOptimizerRows');
    if (rows) rows.innerHTML = panelHtml(result.stats);

    document.querySelectorAll('#openList .order.open').forEach(card => {
      if (card.textContent.includes('Adaptive Target')) return;
      const state = read();
      const idx = Array.from(document.querySelectorAll('#openList .order.open')).indexOf(card);
      const t = (state.open || [])[idx];
      if (!t || !t.adaptiveTarget) return;
      const note = document.createElement('div');
      note.style.marginTop = '6px';
      note.style.padding = '6px 8px';
      note.style.border = '1px solid #18e59a66';
      note.style.borderRadius = '10px';
      note.style.background = '#18e59a12';
      note.style.color = '#18e59a';
      note.style.fontWeight = '900';
      note.style.fontSize = '12px';
      note.textContent = `Adaptive Target: ${fmt(t.adaptiveTarget)} (${Number(t.targetMultiplier||1).toFixed(2)}x, ${t.targetOptimizerSamples} samples)`;
      card.appendChild(note);
    });
  }

  setInterval(renderOptimizer, 3000);
  setTimeout(renderOptimizer, 700);
})();

(function(){
  if (!location.pathname.endsWith('agent-trade-tracker-v2.html')) return;

  const STORAGE_KEY = 'spotLongPaperTradesV2';

  function readTrackerState(){
    try {
      return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{"open":[],"closed":[]}');
    } catch (_) {
      return { open: [], closed: [] };
    }
  }

  function elapsedText(iso){
    const start = new Date(iso).getTime();
    if (!Number.isFinite(start)) return '—';
    const total = Math.max(0, Math.floor((Date.now() - start) / 1000));
    const days = Math.floor(total / 86400);
    const hours = Math.floor((total % 86400) / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    if (days > 0) return `${days}d ${hours}h ${minutes}m`;
    if (hours > 0) return `${hours}h ${minutes}m ${seconds}s`;
    if (minutes > 0) return `${minutes}m ${seconds}s`;
    return `${seconds}s`;
  }

  function injectTimers(){
    const state = readTrackerState();
    const openList = document.getElementById('openList');
    if (!openList || !Array.isArray(state.open)) return;

    const cards = Array.from(openList.querySelectorAll('.order.open'));
    cards.forEach((card, index) => {
      const trade = state.open[index];
      if (!trade || !trade.openedAt) return;
      let timer = card.querySelector('.trade-elapsed-timer');
      if (!timer) {
        timer = document.createElement('div');
        timer.className = 'trade-elapsed-timer';
        timer.style.marginTop = '6px';
        timer.style.padding = '6px 8px';
        timer.style.border = '1px solid #ffd16666';
        timer.style.borderRadius = '10px';
        timer.style.background = '#ffd16614';
        timer.style.color = '#ffd166';
        timer.style.fontWeight = '900';
        timer.style.fontSize = '12px';
        card.appendChild(timer);
      }
      timer.textContent = `Waiting: ${elapsedText(trade.openedAt)}`;
    });
  }

  setInterval(injectTimers, 1000);
  window.addEventListener('storage', injectTimers);
  setTimeout(injectTimers, 500);
})();
