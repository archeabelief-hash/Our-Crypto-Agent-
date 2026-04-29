# Coinbase Magician

Real-time Coinbase market analytics dashboard with paper-mode simulation.

---

# 🔗 Live App Pages

Use these links directly (no need to ask again):

## 🧠 Core Pages

- **Home (Dashboard)**  
  `/home.html`

- **Live Decision V2 (Main Scanner)**  
  `/live-decision-v2.html`

- **Adaptive Trade Tracker (V4)**  
  `/agent-trade-tracker-v4.html`

---

## 📊 Analysis Tools

- **Manual Analyzer**  
  `/manual-selected.html`

- **Liquidity PnL Engine**  
  `/liquidity-pnl-engine.html`

- **Wave Test (Manual Trade Copy Tool)**  
  `/live-wave-test.html`

---

## 🧪 Legacy / Reference

- **Live Decision V1**  
  `/live-decision.html`

- **Trade Tracker V2**  
  `/agent-trade-tracker-v2.html`

- **Trade Tracker V3 (Fixed Size)**  
  `/agent-trade-tracker-v3.html`

---

# ⚙️ How to Use (Correct Flow)

### 1. Start Here

Open:

```text
/home.html
```

---

### 2. Find Trades

Go to:

```text
/live-decision-v2.html
```

Wait for:

```text
READY LONG
```

---

### 3. Execute (Paper / Simulation)

Open:

```text
/agent-trade-tracker-v4.html
```

This will:

- Read V2 signals
- Test multiple position sizes
- Choose the most profitable size
- Track outcome automatically

---

# 🧠 System Overview

## V2 Engine

- Imbalance momentum tracking
- Spread spike detection
- Volatility-adjusted thresholds
- Hollow book detection
- Maker-first execution logic

## V4 Tracker

- Liquidity simulation (real fills)
- Adaptive position sizing
- Only executes profitable trades
- Tracks real PnL after fees + impact

---

# 🚨 Important

This system:

- Uses **real market data**
- Uses **simulated execution**
- Does **NOT place real trades**

---

## Run locally

```bash
npm install
cp .env.example .env
npm start
```

Open:

```text
http://127.0.0.1:8787/home.html
```

---

## Current scope

This build uses public market data and simulated paper-mode actions only. No real orders are sent.
