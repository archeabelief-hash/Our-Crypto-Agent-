const APP_VERSION = '2026-04-28-clean-ui-v1';

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
      'agent-trade-tracker-v4.html': 'Execution Tracker',
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
    setText('#decision + .small', 'Loading Coinbase book.');
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

  if (path.endsWith('agent-trade-tracker-v4.html')) {
    document.title = 'Execution Tracker';
    setText('h1', 'Execution Tracker');
    relabelLinks({
      'home.html': 'Home',
      'live-decision-v2.html': 'AI Trade Engine',
      'liquidity-pnl-engine.html': 'Liquidity PnL',
      'agent-trade-tracker-v3.html': 'Legacy Tracker'
    });
    const subtitle = document.querySelector('.top .small');
    if (subtitle) subtitle.textContent = 'Reads the AI Trade Engine, accepts READY LONG only, sweeps liquidity tiers, picks the best profitable size under your max buy-in, then opens paper trades. No real trades are placed.';
    document.querySelectorAll('h2').forEach(h => {
      h.textContent = h.textContent
        .replace('Open Adaptive Paper Trades', 'Open Execution Tests')
        .replace('Closed Paper Trades', 'Closed Execution Tests')
        .replace('Tracker JSON', 'Execution JSON');
    });
  }
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
