const APP_VERSION = '2026-04-28-v3';

(function(){
  const links = document.querySelectorAll('a[href$=".html"]');
  links.forEach(link => {
    if (!link.href.includes('?v=')) {
      link.href = link.href + '?v=' + APP_VERSION;
    }
  });
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
