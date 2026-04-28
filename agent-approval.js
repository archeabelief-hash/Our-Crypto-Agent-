(() => {
  const STORAGE_KEY = 'agentApprovalState';
  const DEFAULT_STATE = {
    count: 0,
    lastApprovedAt: null,
    lastPair: null,
    lastSignal: null,
    lastConfidence: null,
    history: []
  };

  const $ = (id) => document.getElementById(id);

  function readState() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? { ...DEFAULT_STATE, ...JSON.parse(raw) } : { ...DEFAULT_STATE };
    } catch (_) {
      return { ...DEFAULT_STATE };
    }
  }

  function writeState(state) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  }

  function getCurrentSnapshot() {
    const signal = $('sig')?.textContent?.trim() || 'UNKNOWN';
    const pair = $('pair')?.textContent?.trim() || 'UNKNOWN';
    const confidenceText = $('conf')?.textContent?.trim() || '';
    const pnlText = $('pnlGate')?.textContent?.trim() || '';
    const spoofText = $('spoofRisk')?.textContent?.trim() || '';
    const confidence = Number(confidenceText.replace('%', ''));
    const pnl = Number(pnlText.replace('$', '').replace(',', ''));
    const spoofRisk = Number(spoofText.replace('%', ''));
    return { signal, pair, confidence, pnl, spoofRisk };
  }

  function isApprovalAllowed(snapshot) {
    if (!snapshot.signal || snapshot.signal === 'WAIT' || snapshot.signal === 'WAIT_NO_CLEAR_PROFIT' || snapshot.signal === 'LOADING') {
      return { ok: false, reason: 'Approval blocked: signal is not valid after risk gates.' };
    }
    if (!Number.isFinite(snapshot.pnl) || snapshot.pnl <= 0) {
      return { ok: false, reason: 'Approval blocked: Actual PnL gate is not positive.' };
    }
    if (Number.isFinite(snapshot.spoofRisk) && snapshot.spoofRisk > 60) {
      return { ok: false, reason: 'Approval blocked: spoof risk is too high.' };
    }
    return { ok: true, reason: 'Approval accepted.' };
  }

  function approvalSummary(state) {
    return {
      approvalCount: state.count,
      lastApprovedAt: state.lastApprovedAt,
      lastPair: state.lastPair,
      lastSignal: state.lastSignal,
      lastConfidence: state.lastConfidence
    };
  }

  function patchJson(state) {
    const jsonEl = $('json');
    if (!jsonEl) return;
    try {
      const parsed = JSON.parse(jsonEl.textContent || '{}');
      parsed.approvalState = approvalSummary(state);
      jsonEl.textContent = JSON.stringify(parsed, null, 2);
    } catch (_) {
      // JSON may be mid-render; next interval will patch again.
    }
  }

  function renderApprovalPanel(message = '') {
    const state = readState();
    const panel = $('approvalPanel');
    if (!panel) return;
    panel.innerHTML = `
      <h2>Agent Approval Tracker</h2>
      <div class="metrics">
        <div class="metric"><div class="label">Approvals</div><div class="value" id="approvalCount">${state.count}</div></div>
        <div class="metric"><div class="label">Last Pair</div><div class="value">${state.lastPair || '—'}</div></div>
        <div class="metric"><div class="label">Last Signal</div><div class="value">${state.lastSignal || '—'}</div></div>
        <div class="metric"><div class="label">Last Confidence</div><div class="value">${state.lastConfidence ?? '—'}${state.lastConfidence === null ? '' : '%'}</div></div>
        <div class="metric"><div class="label">Last Approval</div><div class="value" style="font-size:14px;white-space:normal">${state.lastApprovedAt ? new Date(state.lastApprovedAt).toLocaleString() : '—'}</div></div>
        <div class="metric"><div class="label">Status</div><div class="value" style="font-size:14px;white-space:normal">${message || 'Waiting for approval.'}</div></div>
      </div>
      <div class="controls" style="margin-top:10px">
        <button id="approveSignal">Approve Current Signal</button>
        <button id="resetApprovals">Reset Approval Count</button>
      </div>
      <div class="small" style="margin-top:8px">Approvals are stored locally in this browser only. They are not committed to GitHub.</div>
    `;
    $('approveSignal').onclick = approveCurrentSignal;
    $('resetApprovals').onclick = resetApprovals;
    patchJson(state);
  }

  function approveCurrentSignal() {
    const snapshot = getCurrentSnapshot();
    const gate = isApprovalAllowed(snapshot);
    if (!gate.ok) {
      renderApprovalPanel(gate.reason);
      return;
    }
    const state = readState();
    const event = {
      approvedAt: new Date().toISOString(),
      pair: snapshot.pair,
      signal: snapshot.signal,
      confidence: Number.isFinite(snapshot.confidence) ? snapshot.confidence : null,
      pnl: Number.isFinite(snapshot.pnl) ? snapshot.pnl : null,
      spoofRisk: Number.isFinite(snapshot.spoofRisk) ? snapshot.spoofRisk : null
    };
    const next = {
      ...state,
      count: state.count + 1,
      lastApprovedAt: event.approvedAt,
      lastPair: event.pair,
      lastSignal: event.signal,
      lastConfidence: event.confidence,
      history: [event, ...(state.history || [])].slice(0, 50)
    };
    writeState(next);
    renderApprovalPanel('Approval accepted and saved locally.');
  }

  function resetApprovals() {
    writeState({ ...DEFAULT_STATE });
    renderApprovalPanel('Approval count reset.');
  }

  function mountPanel() {
    if ($('approvalPanel')) return;
    const section = document.createElement('section');
    section.className = 'card panel';
    section.id = 'approvalPanel';
    const signalFeed = document.querySelector('section.card.panel:last-of-type');
    if (signalFeed && signalFeed.parentNode) {
      signalFeed.parentNode.insertBefore(section, signalFeed);
    } else {
      document.querySelector('.app')?.appendChild(section);
    }
    renderApprovalPanel();
  }

  function start() {
    mountPanel();
    setInterval(() => patchJson(readState()), 1200);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
