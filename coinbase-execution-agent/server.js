import express from 'express';
import cors from 'cors';
import dotenv from 'dotenv';
import crypto from 'crypto';
import { v4 as uuidv4 } from 'uuid';

dotenv.config();

const app = express();
app.use(cors());
app.use(express.json({ limit: '1mb' }));

const PORT = process.env.PORT || 8788;
const MODE = 'PAPER_SIMULATION_ONLY';

const SUPPORTED = new Set([
  'BTC-USD', 'ETH-USD', 'SOL-USD', 'XLM-USD', 'DOGE-USD', 'SHIB-USD',
  'XRP-USD', 'ADA-USD', 'AVAX-USD', 'LINK-USD', 'LTC-USD'
]);

const sessions = new Map();
const duplicateLocks = new Map();

function getClientId(req){
  const explicit = req.body?.userId || req.body?.sessionId || req.headers['x-user-id'] || req.headers['x-session-id'];
  if(explicit && String(explicit).trim()) return String(explicit).trim();
  const ip = String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || 'unknown').split(',')[0].trim();
  const ua = String(req.headers['user-agent'] || 'unknown');
  return crypto.createHash('sha256').update(`${ip}|${ua}`).digest('hex').slice(0, 24);
}

function makeSession(sessionId){
  return {
    sessionId,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    positions: {},
    realizedPnl: 0,
    wins: 0,
    losses: 0,
    lastSignal: null,
    lastDecision: null,
    lastOrder: null,
    rejections: [],
    orders: []
  };
}

function getSession(req){
  const id = getClientId(req);
  if(!sessions.has(id)) sessions.set(id, makeSession(id));
  const session = sessions.get(id);
  session.updatedAt = new Date().toISOString();
  return session;
}

function publicState(session){
  const total = session.wins + session.losses;
  return {
    sessionId: session.sessionId,
    createdAt: session.createdAt,
    updatedAt: session.updatedAt,
    openPositions: session.positions,
    realizedPnl: session.realizedPnl,
    wins: session.wins,
    losses: session.losses,
    winRate: total ? Math.round((session.wins / total) * 100) : 0,
    lastSignal: session.lastSignal,
    lastDecision: session.lastDecision,
    lastOrder: session.lastOrder,
    rejections: session.rejections.slice(0, 20),
    orders: session.orders.slice(-20)
  };
}

function reject(session, reason, signal){
  const rec = { ok:false, reason, at:new Date().toISOString(), action: signal?.action, pair: signal?.pair, sessionId: session.sessionId };
  session.lastDecision = rec;
  session.rejections.unshift(rec);
  if(session.rejections.length > 50) session.rejections.pop();
  return rec;
}

function accept(session, decision){
  const rec = { ...decision, sessionId: session.sessionId, at: new Date().toISOString() };
  session.lastDecision = rec;
  return rec;
}

function validSignal(s){
  if(!s) return 'missing signal';
  if(!SUPPORTED.has(s.pair)) return 'unsupported pair';
  if(s.readiness < 100) return 'readiness < 100';
  if(!['LONG_WATCH','EXIT_WATCH'].includes(s.action)) return 'unsupported action';
  if(!Number.isFinite(s.confidence) || s.confidence < 72) return 'confidence < 72';
  if(!Number.isFinite(s.bestBid) || !Number.isFinite(s.bestAsk)) return 'missing bestBid/bestAsk';
  if(!Number.isFinite(s.spread)) return 'missing spread';
  if(!s.walls || !Number.isFinite(s.walls.spoofRisk)) return 'missing spoof risk';
  if(s.walls.spoofRisk > 62) return 'spoof risk too high';
  if(!s.trap || !Number.isFinite(s.trap.score)) return 'missing trap score';
  if(s.trap.score > 62) return 'trap score too high';
  if(!s.risk || !Number.isFinite(s.risk.suggestedEntry) || !Number.isFinite(s.risk.suggestedTarget) || !Number.isFinite(s.risk.suggestedStop)) return 'missing risk fields';
  return null;
}

function isDuplicate(session, s){
  const key = `${session.sessionId}:${s.pair}:${s.action}`;
  const last = duplicateLocks.get(key) || 0;
  if(Date.now() - last < 60_000) return true;
  duplicateLocks.set(key, Date.now());
  return false;
}

app.get('/health', (req,res)=>{
  res.json({ ok:true, mode: MODE, activeSessions: sessions.size, time: new Date().toISOString() });
});

app.get('/status', (req,res)=>{
  const session = getSession(req);
  res.json({ ok:true, mode: MODE, ...publicState(session) });
});

app.get('/positions', (req,res)=>{
  const session = getSession(req);
  res.json({ ok:true, sessionId: session.sessionId, positions: session.positions });
});

app.post('/reset-paper', (req,res)=>{
  const id = getClientId(req);
  const session = makeSession(id);
  sessions.set(id, session);
  res.json({ ok:true, message:'Paper session reset for this user only.', mode: MODE, ...publicState(session) });
});

app.post('/clear-paper', (req,res)=>{
  const session = getSession(req);
  session.positions = {};
  session.lastOrder = { type:'CLEAR_PAPER_POSITIONS', at:new Date().toISOString() };
  res.json({ ok:true, message:'This user paper positions cleared.', mode: MODE, ...publicState(session) });
});

app.post('/signal', (req,res)=>{
  const session = getSession(req);
  const s = req.body;
  session.lastSignal = s;

  const err = validSignal(s);
  if(err) return res.json(reject(session, err, s));
  if(isDuplicate(session, s)) return res.json(reject(session, 'duplicate same pair/action within 60 seconds', s));

  const pair = s.pair;

  if(s.action === 'LONG_WATCH'){
    if(session.positions[pair]) return res.json(reject(session, 'already holding position for this pair', s));

    const entry = s.risk.suggestedEntry || s.bestAsk;
    const usd = s.risk.maxPositionUsd || 10;
    const size = usd / entry;

    const position = {
      id: uuidv4(),
      pair,
      entry,
      size,
      costUsd: usd,
      target: s.risk.suggestedTarget,
      stop: s.risk.suggestedStop,
      openedAt: new Date().toISOString(),
      confidence: s.confidence,
      reason: s.reason || null
    };

    session.positions[pair] = position;
    const order = { type:'PAPER_BUY', pair, entry, size, usd, positionId: position.id, at:new Date().toISOString() };
    session.lastOrder = order;
    session.orders.push(order);
    return res.json(accept(session, { ok:true, action:'PAPER_BUY', pair, entry, size, position }));
  }

  if(s.action === 'EXIT_WATCH'){
    const pos = session.positions[pair];
    if(!pos) return res.json(reject(session, 'no position to exit for this user/pair', s));

    const exit = s.bestBid || s.risk.suggestedEntry;
    const pnl = (exit - pos.entry) * pos.size;
    const pnlPct = ((exit - pos.entry) / pos.entry) * 100;

    session.realizedPnl += pnl;
    pnl >= 0 ? session.wins++ : session.losses++;
    delete session.positions[pair];

    const order = { type:'PAPER_SELL', pair, exit, pnl, pnlPct, positionId: pos.id, at:new Date().toISOString() };
    session.lastOrder = order;
    session.orders.push(order);
    return res.json(accept(session, { ok:true, action:'PAPER_SELL', pair, exit, pnl, pnlPct }));
  }

  return res.json(reject(session, 'unhandled action', s));
});

app.listen(PORT, ()=>{
  console.log(`Paper agent running on http://127.0.0.1:${PORT} with per-user sessions`);
});
