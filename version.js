const APP_VERSION = '2026-04-28-clean-active-ui-v2';

(function(){
  const links = document.querySelectorAll('a[href$=".html"]');
  links.forEach(link => {
    if (!link.href.includes('?v=')) link.href = link.href + '?v=' + APP_VERSION;
  });
})();

function buildAdaptiveTargetStats(){
  const STORAGE_KEY = 'executionTrackerActiveV1';
  const MIN_SAMPLES = 4;
  let state;
  try { state = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{"closed":[]}'); }
  catch (_) { state = { closed: [] }; }
  const groups = {};
  (state.closed || []).forEach(t => {
    if (!t || !t.tf) return;
    const planned = (Number(t.originalTarget || t.target) - Number(t.entry)) * Number(t.qty || 0);
    if (!Number.isFinite(planned) || planned <= 0) return;
    const ratio = Math.max(0, Number(t.mfe || 0) / planned);
    if (!groups[t.tf]) groups[t.tf] = [];
    groups[t.tf].push({ ratio, win: Number(t.pnl || 0) >= 0 });
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
    out[tf] = { samples: rows.length, avg, p50, p70, hitRate, multiplier, active: rows.length >= MIN_SAMPLES };
  });
  return out;
}

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

  function hideLegacyLinks(){
    const legacy = ['live-decision.html','agent-trade-tracker-v2.html','agent-trade-tracker-v3.html','agent-trade-tracker-v4.html'];
    document.querySelectorAll('a').forEach(a => {
      const raw = a.getAttribute('href') || '';
      if (legacy.some(x => raw.includes(x))) a.style.display = 'none';
    });
  }

  function cleanCommonLabels(){
    relabelLinks({
      'home.html': 'Home',
      'live-decision-v2.html': 'AI Trade Engine',
      'execution-tracker.html': 'Execution Tracker',
      'manual-selected.html': 'Manual Analyzer',
      'liquidity-pnl-engine.html': 'Liquidity PnL',
      'live-wave-test.html': 'Wave Test',
      'agent.html': 'Agent View',
      'install.html': 'Install'
    });
    hideLegacyLinks();
  }

  cleanCommonLabels();

  if (path.endsWith('home.html') || path.endsWith('/')) {
    const footer = document.querySelector('.footer');
    if (footer) footer.textContent = 'Coinbase Magician — active stack only';
  }

  if (path.endsWith('live-decision-v2.html')) {
    document.title = 'AI Trade Engine';
    setText('h1', 'AI Trade Engine');
    setText('section.card h2', 'Engine Controls');
    const subtitle = document.querySelector('.top .small');
    if (subtitle) subtitle.textContent = 'Behavior-aware trade engine with adaptive targets, entry timing read, liquidity filters, spread-spike protection, and momentum diagnostics.';
    const reset = document.getElementById('resetMemory');
    if (reset) reset.textContent = 'Reset Engine Memory';
    document.querySelectorAll('h2').forEach(h => {
      h.textContent = h.textContent
        .replace('Best V2 Decision', 'Best AI Trade Decision')
        .replace('V2 Interval Scanner', 'AI Interval Scanner')
        .replace('V2 Diagnostics', 'Engine Diagnostics');
    });
    document.querySelectorAll('.label').forEach(el => { el.textContent = el.textContent.replace('Score V2', 'Engine Score'); });
  }

  if (path.endsWith('execution-tracker.html')) {
    document.title = 'Execution Tracker';
    setText('h1', 'Execution Tracker');
  }

  if (path.endsWith('liquidity-pnl-engine.html')) {
    relabelLinks({'live-decision.html':'AI Trade Engine','manual-selected.html':'Manual Analyzer'});
    document.querySelectorAll('a').forEach(a => {
      if ((a.getAttribute('href') || '').includes('live-decision.html')) a.setAttribute('href','live-decision-v2.html');
    });
  }

  if (path.endsWith('live-wave-test.html')) {
    relabelLinks({'live-decision.html':'AI Trade Engine','manual-selected.html':'Manual Analyzer','agent.html':'Agent View'});
    document.querySelectorAll('a').forEach(a => {
      if ((a.getAttribute('href') || '').includes('live-decision.html')) a.setAttribute('href','live-decision-v2.html');
    });
  }
})();

(function(){
  if (!location.pathname.endsWith('live-decision-v2.html')) return;

  function adjustPlan(plan, stats){
    if (!plan || plan.side !== 'LONG' || !stats || !stats[plan.tf] || !stats[plan.tf].active) return plan;
    const s = stats[plan.tf];
    const entry = Number(plan.entry);
    const target = Number(plan.target);
    if (!Number.isFinite(entry) || !Number.isFinite(target) || target <= entry) return plan;
    const originalTarget = Number(plan.originalTarget || target);
    const baseMove = originalTarget - entry;
    const adjustedTarget = entry + baseMove * s.multiplier;
    if (!Number.isFinite(adjustedTarget) || adjustedTarget <= entry) return plan;
    plan.originalTarget = originalTarget;
    plan.target = adjustedTarget;
    plan.adaptiveTarget = adjustedTarget;
    plan.targetMultiplier = s.multiplier;
    plan.targetOptimizerSamples = s.samples;
    plan.targetOptimizerHitRate = s.hitRate;
    plan.targetOptimizerActive = true;
    if (plan.pnl && Number.isFinite(Number(plan.pnl.actual))) {
      const oldMove = originalTarget - entry;
      const newMove = adjustedTarget - entry;
      const scale = oldMove > 0 ? newMove / oldMove : 1;
      plan.pnl.actual = Number(plan.pnl.actual) * scale;
      plan.pnl.gross = Number(plan.pnl.gross || plan.pnl.actual) * scale;
    }
    return plan;
  }

  function patchEngineJson(){
    const pre = document.getElementById('json');
    if (!pre || !pre.textContent || pre.textContent.includes('targetOptimizerApplied')) return;
    let data;
    try { data = JSON.parse(pre.textContent); } catch (_) { return; }
    const stats = buildAdaptiveTargetStats();
    let applied = 0;
    if (Array.isArray(data.allIntervals)) {
      data.allIntervals = data.allIntervals.map(p => {
        const before = p && p.target;
        const out = adjustPlan(p, stats);
        if (out && out.targetOptimizerActive && out.target !== before) applied++;
        return out;
      });
    }
    if (data.best) {
      const before = data.best.target;
      data.best = adjustPlan(data.best, stats);
      if (data.best && data.best.targetOptimizerActive && data.best.target !== before) applied++;
    }
    data.targetOptimizerApplied = applied > 0;
    data.targetOptimizerStats = stats;
    data.targetOptimizerNote = applied > 0 ? 'Targets adjusted by Execution Tracker MFE/hit-rate feedback.' : 'Target optimizer learning or no matching active timeframe yet.';
    pre.textContent = JSON.stringify(data, null, 2);

    let card = document.getElementById('engineTargetOptimizerCard');
    const diag = Array.from(document.querySelectorAll('section.card')).find(s => (s.querySelector('h2')||{}).textContent.includes('Engine Diagnostics'));
    if (!card && diag) {
      card = document.createElement('section');
      card.className = 'card';
      card.id = 'engineTargetOptimizerCard';
      card.innerHTML = '<h2>Adaptive Target Feedback</h2><div id="engineTargetOptimizerBody" class="small"></div>';
      diag.insertAdjacentElement('afterend', card);
    }
    const body = document.getElementById('engineTargetOptimizerBody');
    if (body) {
      const keys = Object.keys(stats);
      body.innerHTML = keys.length ? keys.map(tf => {
        const s = stats[tf];
        return `${tf}: ${s.active ? 'ACTIVE' : 'LEARNING'} | samples ${s.samples} | hit ${(s.hitRate*100).toFixed(1)}% | target ${s.multiplier.toFixed(2)}x`;
      }).join('<br>') : 'Waiting for Execution Tracker closed-trade MFE data.';
    }
  }

  setInterval(patchEngineJson, 1200);
  setTimeout(patchEngineJson, 1000);
})();

(function(){
  if (!location.pathname.endsWith('execution-tracker.html')) return;

  const STORAGE_KEY = 'executionTrackerActiveV1';
  function read(){ try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '{"open":[],"closed":[],"blocked":[]}'); } catch (_) { return { open: [], closed: [], blocked: [] }; } }
  function write(state){ localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }
  function fmt(n){ return Number.isFinite(n) ? n.toLocaleString(undefined,{maximumFractionDigits:Math.abs(n)>=1?6:8}) : '—'; }

  function optimizeOpenTargets(){
    const state = read();
    const stats = buildAdaptiveTargetStats();
    let changed = false;
    (state.open || []).forEach(t => {
      const s = stats[t.tf];
      if (!s || !s.active) return;
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

  function renderOptimizer(){
    const result = optimizeOpenTargets();
    let card = document.getElementById('targetOptimizerCard');
    const summaryCard = Array.from(document.querySelectorAll('section.card')).find(s => (s.querySelector('h2')||{}).textContent === 'Summary');
    if (!card && summaryCard) {
      card = document.createElement('section');
      card.className = 'card';
      card.id = 'targetOptimizerCard';
      card.innerHTML = '<h2>Adaptive Target Optimizer</h2><div class="small">Uses closed-trade MFE and hit-rate data to tighten unrealistic targets by timeframe. This feeds back into the AI Trade Engine JSON.</div><div id="targetOptimizerRows" class="orders" style="margin-top:10px"></div>';
      summaryCard.insertAdjacentElement('afterend', card);
    }
    const rows = document.getElementById('targetOptimizerRows');
    if (rows) {
      const keys = Object.keys(result.stats);
      rows.innerHTML = keys.length ? keys.map(tf => {
        const s = result.stats[tf];
        return `<div class="order ${s.active?'open':'block'}"><b>${tf}</b> <span class="pill ${s.active?'pill-good':'pill-warn'}">${s.active?'ACTIVE':'LEARNING'}</span><br>Samples ${s.samples} | Hit Rate ${(s.hitRate*100).toFixed(1)}%<br>Avg MFE ${(s.avg*100).toFixed(1)}% of target | P70 ${(s.p70*100).toFixed(1)}%<br>Target Multiplier <b>${s.multiplier.toFixed(2)}x</b></div>`;
      }).join('') : '<div class="small">Waiting for closed trades with MFE data.</div>';
    }

    document.querySelectorAll('#openList .order.open').forEach(card => {
      if (card.textContent.includes('Adaptive Target')) return;
      const state = read();
      const idx = Array.from(document.querySelectorAll('#openList .order.open')).indexOf(card);
      const t = (state.open || [])[idx];
      if (!t || !t.adaptiveTarget) return;
      const note = document.createElement('div');
      note.style.marginTop = '6px'; note.style.padding = '6px 8px'; note.style.border = '1px solid #18e59a66'; note.style.borderRadius = '10px'; note.style.background = '#18e59a12'; note.style.color = '#18e59a'; note.style.fontWeight = '900'; note.style.fontSize = '12px';
      note.textContent = `Adaptive Target: ${fmt(t.adaptiveTarget)} (${Number(t.targetMultiplier||1).toFixed(2)}x, ${t.targetOptimizerSamples} samples)`;
      card.appendChild(note);
    });
  }

  setInterval(renderOptimizer, 3000);
  setTimeout(renderOptimizer, 700);
})();
