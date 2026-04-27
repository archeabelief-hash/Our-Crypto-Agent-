import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import { v4 as uuidv4 } from 'uuid';

dotenv.config();

const app = express();
app.use(cors());
app.use(express.json());

const PORT = process.env.PORT || 8788;

// Modes: PAPER (default), DRY_RUN, LIVE_DISABLED (explicit), LIVE_ENABLED (must be toggled manually)
let MODE = process.env.MODE || 'PAPER';

const SUPPORTED = new Set(['SOL-USD','XLM-USD','BTC-USD']);

// In-memory state (replace with DB later if needed)
const state = {
  positions: {}, // { pair: { entry, size, costUsd, target, stop, openedAt } }
  realizedPnl: 0,
  wins: 0,
  losses: 0,
  lastSignal: null,
  lastDecision: null,
  lastOrder: null,
  rejections: []
};

function reject(reason, signal){
  const rec = { ok:false, reason, at:new Date().toISOString(), action: signal?.action, pair: signal?.pair };
  state.lastDecision = rec;
  state.rejections.unshift(rec);
  if(state.rejections.length>50) state.rejections.pop();
  return rec;
}

function accept(decision){
  state.lastDecision = decision;
  return decision;
}

function validSignal(s){
  if(!s) return 'missing signal';
  if(!SUPPORTED.has(s.pair)) return 'unsupported pair';
  if(s.readiness < 100) return 'readiness < 100';
  if(!['LONG_WATCH','EXIT_WATCH'].includes(s.action)) return 'unsupported action';
  if(!Number.isFinite(s.confidence) || s.confidence < 72) return 'confidence < 72';
  if(!s.bestBid || !s.bestAsk) return 'missing bestBid/bestAsk';
  if(!Number.isFinite(s.spread)) return 'missing spread';
  if(!s.walls || s.walls.spoofRisk > 62) return 'spoof risk too high';
  if(!s.trap || s.trap.score > 62) return 'trap score too high';
  if(!s.risk || !s.risk.suggestedEntry || !s.risk.suggestedTarget || !s.risk.suggestedStop) return 'missing risk fields';
  return null;
}

app.get('/health', (req,res)=> res.json({ ok:true, mode: MODE, time: new Date().toISOString() }));

app.get('/status', (req,res)=>{
  const total = state.wins + state.losses;
  res.json({
    ok:true,
    mode: MODE,
    positions: state.positions,
    realizedPnl: state.realizedPnl,
    wins: state.wins,
    losses: state.losses,
    winRate: total ? Math.round((state.wins/total)*100) : 0,
    lastSignal: state.lastSignal,
    lastDecision: state.lastDecision,
    lastOrder: state.lastOrder,
    rejections: state.rejections
  });
});

app.post('/mode', (req,res)=>{
  const { mode } = req.body || {};
  if(!mode) return res.status(400).json({ ok:false, error:'missing mode' });
  MODE = mode;
  res.json({ ok:true, mode: MODE });
});

app.post('/kill-switch', (req,res)=>{
  MODE = 'LIVE_DISABLED';
  state.positions = {};
  res.json({ ok:true, mode: MODE, message:'All positions cleared (paper) and live disabled.' });
});

app.post('/signal', (req,res)=>{
  const s = req.body;
  state.lastSignal = s;

  const err = validSignal(s);
  if(err) return res.json(reject(err, s));

  const pair = s.pair;

  // LONG_WATCH
  if(s.action === 'LONG_WATCH'){
    if(state.positions[pair]) return res.json(reject('already holding position', s));

    const entry = s.risk.suggestedEntry || s.bestAsk;
    const usd = s.risk.maxPositionUsd || 10;
    const size = usd / entry;

    state.positions[pair] = {
      id: uuidv4(),
      entry,
      size,
      costUsd: usd,
      target: s.risk.suggestedTarget,
      stop: s.risk.suggestedStop,
      openedAt: new Date().toISOString(),
      confidence: s.confidence
    };

    state.lastOrder = { type:'PAPER_BUY', pair, entry, size, usd };
    return res.json(accept({ ok:true, action:'PAPER_BUY', pair, entry, size }));
  }

  // EXIT_WATCH
  if(s.action === 'EXIT_WATCH'){
    const pos = state.positions[pair];
    if(!pos) return res.json(reject('no position to exit', s));

    const exit = s.bestBid || s.risk.suggestedEntry;
    const pnl = (exit - pos.entry) * pos.size;

    state.realizedPnl += pnl;
    pnl >= 0 ? state.wins++ : state.losses++;

    delete state.positions[pair];

    state.lastOrder = { type:'PAPER_SELL', pair, exit, pnl };
    return res.json(accept({ ok:true, action:'PAPER_SELL', pair, exit, pnl }));
  }

  return res.json(reject('unhandled action', s));
});

app.listen(PORT, ()=>{
  console.log(`Execution Agent running on http://127.0.0.1:${PORT} in ${MODE} mode`);
});
