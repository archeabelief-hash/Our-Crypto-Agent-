(function(){
  if (!location.pathname.endsWith('live-decision-v2.html')) return;

  const KEY = 'antiSpoofFlowMemoryV1';

  function read(){
    try { return JSON.parse(sessionStorage.getItem(KEY) || '[]'); }
    catch (_) { return []; }
  }

  function write(rows){
    sessionStorage.setItem(KEY, JSON.stringify(rows.slice(-40)));
  }

  function avg(arr){ return arr.length ? arr.reduce((s,x)=>s+x,0)/arr.length : 0; }
  function clamp(n,a,b){ return Math.max(a, Math.min(b,n)); }

  function rowFromEngine(data){
    const d = data.diagnostics || {};
    const best = data.best || {};
    return {
      t: Date.now(),
      pair: data.pair || 'UNKNOWN',
      side: best.side || 'NONE',
      score: Number(best.score || 0),
      imbalance: Number(d.im || 0),
      momentum: Number(d.momentum || 0),
      spreadSpike: Number(d.spreadSpike || 1),
      hollow: Number(best.hollowFactor || 1),
      wall: Number(best.wallFactor || 1),
      status: best.status || data.decision || 'NO_SETUP'
    };
  }

  function remember(data){
    const row = rowFromEngine(data);
    const rows = read().filter(x => x.pair === row.pair && Date.now() - x.t < 120000);
    rows.push(row);
    write(rows);
    return rows;
  }

  function analyze(rows){
    if (rows.length < 5) {
      return {
        active:false,
        verdict:'LEARNING',
        quality:0.5,
        spoofRisk:0,
        vanishRisk:0,
        flowSpeed:0,
        wallPersistence:0,
        samples:rows.length
      };
    }

    const last = rows[rows.length - 1];
    const prev = rows[rows.length - 2];
    const recent = rows.slice(-8);
    const older = rows.slice(0, Math.max(1, rows.length - recent.length));

    const flowSpeed = Number(last.momentum || 0) - Number(prev.momentum || 0);
    const scoreSpeed = Number(last.score || 0) - Number(prev.score || 0);
    const spreadStress = Math.max(0, Number(last.spreadSpike || 1) - 1);
    const hollowStress = Math.max(0, 0.85 - Number(last.hollow || 1));

    const recentImbAbs = avg(recent.map(x => Math.abs(Number(x.imbalance || 0))));
    const olderImbAbs = avg(older.map(x => Math.abs(Number(x.imbalance || 0))));
    const pressureJump = recentImbAbs - olderImbAbs;

    const recentWall = avg(recent.map(x => Number(x.wall || 1)));
    const olderWall = avg(older.map(x => Number(x.wall || 1)));
    const wallPersistence = clamp(recentWall / Math.max(0.01, olderWall), 0, 2);

    const signFlips = recent.slice(1).reduce((n,x,i) => {
      const p = recent[i];
      return n + ((Number(x.imbalance || 0) > 0) !== (Number(p.imbalance || 0) > 0) ? 1 : 0);
    }, 0);

    const vanishRisk = clamp(
      (flowSpeed < -4 ? 0.28 : 0) +
      (scoreSpeed < -10 ? 0.22 : 0) +
      (wallPersistence < 0.75 ? 0.25 : 0) +
      (spreadStress > 0.2 ? 0.2 : 0),
      0, 1
    );

    const spoofRisk = clamp(
      (pressureJump > 18 && wallPersistence < 0.9 ? 0.28 : 0) +
      (signFlips >= 2 ? 0.22 : 0) +
      (hollowStress * 0.7) +
      (spreadStress * 0.4) +
      (Math.abs(flowSpeed) > 18 ? 0.18 : 0),
      0, 1
    );

    const flowQuality = clamp(
      0.55 +
      (flowSpeed > 2 ? 0.16 : 0) +
      (wallPersistence > 1.05 ? 0.14 : 0) -
      (spoofRisk * 0.35) -
      (vanishRisk * 0.35) -
      (spreadStress * 0.25),
      0, 1
    );

    let verdict = 'NORMAL FLOW';
    if (flowQuality >= 0.72) verdict = 'CLEAN BUILDING FLOW';
    if (spoofRisk >= 0.55) verdict = 'SPOOF / FAKE LIQUIDITY RISK';
    if (vanishRisk >= 0.55) verdict = 'LIQUIDITY VANISH RISK';
    if (spoofRisk >= 0.55 && vanishRisk >= 0.55) verdict = 'AVOID: SPOOF + VANISH RISK';

    return {
      active:true,
      verdict,
      quality:flowQuality,
      spoofRisk,
      vanishRisk,
      flowSpeed,
      wallPersistence,
      pressureJump,
      spreadStress,
      signFlips,
      samples:rows.length
    };
  }

  function renderPanel(stats){
    let card = document.getElementById('antiSpoofFlowCard');
    const anchor = document.getElementById('entryTimingV2Card') || document.getElementById('engineTargetOptimizerCard') || Array.from(document.querySelectorAll('section.card')).find(x => ((x.querySelector('h2') || {}).textContent || '').includes('Engine Diagnostics'));
    if (!card && anchor) {
      card = document.createElement('section');
      card.className = 'card';
      card.id = 'antiSpoofFlowCard';
      card.innerHTML = '<h2>Anti-Spoof + Order Flow Speed</h2><div id="antiSpoofFlowBody" class="small"></div>';
      anchor.insertAdjacentElement('afterend', card);
    }
    const body = document.getElementById('antiSpoofFlowBody');
    if (!body) return;
    const cls = !stats.active ? 'warn' : stats.spoofRisk >= 0.55 || stats.vanishRisk >= 0.55 ? 'bad' : stats.quality >= 0.72 ? 'good' : 'warn';
    body.innerHTML = '<b class="' + cls + '">' + stats.verdict + '</b><br>' +
      'Flow Quality: ' + stats.quality.toFixed(2) +
      ' | Flow Speed: ' + stats.flowSpeed.toFixed(2) +
      ' | Wall Persistence: ' + stats.wallPersistence.toFixed(2) + 'x<br>' +
      'Spoof Risk: ' + stats.spoofRisk.toFixed(2) +
      ' | Vanish Risk: ' + stats.vanishRisk.toFixed(2) +
      ' | Sign Flips: ' + (stats.signFlips || 0) + '<br>' +
      '<span class="small">Advisory only: helps identify fake pressure, vanishing walls, and clean building order flow before manual or agent use.</span>';
  }

  function patchJson(stats){
    const pre = document.getElementById('json');
    if (!pre || !pre.textContent) return;
    let data;
    try { data = JSON.parse(pre.textContent); }
    catch (_) { return; }
    if (data.antiSpoofFlow) return;
    data.antiSpoofFlow = stats;
    data.antiSpoofFlowNote = 'Advisory market-quality layer only. Does not submit orders.';
    pre.textContent = JSON.stringify(data, null, 2);
  }

  function tick(){
    const pre = document.getElementById('json');
    if (!pre || !pre.textContent) return;
    let data;
    try { data = JSON.parse(pre.textContent); }
    catch (_) { return; }
    if (!data || !data.diagnostics || !data.best) return;
    const rows = remember(data);
    const stats = analyze(rows);
    renderPanel(stats);
    patchJson(stats);
  }

  setInterval(tick, 1600);
  setTimeout(tick, 1600);
})();
