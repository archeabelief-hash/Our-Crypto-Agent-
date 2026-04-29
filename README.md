# Coinbase Magician

Real-time Coinbase market analytics dashboard with paper-mode simulation.

---

# 🌐 LIVE APP (USE THIS LINK)

👉 **Home Dashboard:**  
https://archeabelief-hash.github.io/Our-Crypto-Agent-/home.html

---

# 🔗 Live App Pages

## 🧠 Core Pages

- **Home (Dashboard)**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/home.html

- **Live Decision V2 (Main Scanner)**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/live-decision-v2.html

- **Adaptive Trade Tracker (V4)**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/agent-trade-tracker-v4.html

---

## 📊 Analysis Tools

- **Manual Analyzer**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/manual-selected.html

- **Liquidity PnL Engine**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/liquidity-pnl-engine.html

- **Wave Test (Manual Trade Copy Tool)**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/live-wave-test.html

---

## 🧪 Legacy / Reference

- **Live Decision V1**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/live-decision.html

- **Trade Tracker V2**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/agent-trade-tracker-v2.html

- **Trade Tracker V3 (Fixed Size)**  
  https://archeabelief-hash.github.io/Our-Crypto-Agent-/agent-trade-tracker-v3.html

---

# ⚙️ How to Use (Correct Flow)

### 1. Start Here

Open:

```text
https://archeabelief-hash.github.io/Our-Crypto-Agent-/home.html
```

---

### 2. Find Trades

Go to:

```text
https://archeabelief-hash.github.io/Our-Crypto-Agent-/live-decision-v2.html
```

Wait for:

```text
READY LONG
```

---

### 3. Execute (Paper / Simulation)

Open:

```text
https://archeabelief-hash.github.io/Our-Crypto-Agent-/agent-trade-tracker-v4.html
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
