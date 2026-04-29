# Coinbase Live Agent — Safe Mode Backend

This folder is the isolated backend execution layer for the Coinbase agent.

## Default Safety State

The agent defaults to **dry-run** mode.

```env
TRADE_MODE=dry_run
```

No real Coinbase orders should be sent unless all of these are true:

```text
TRADE_MODE=live
ALLOW_LIVE_ORDERS=true
REQUIRE_MANUAL_APPROVAL=false or valid approval file exists
KILL_SWITCH=false
```

## Important

Never place API keys in GitHub, GitHub Pages, browser JavaScript, HTML, screenshots, or chat messages.

Use a local `.env` file or deployment-provider secrets only.

## Setup

```bash
cd api/live-agent
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp config.example.env .env
python main.py
```

Windows PowerShell:

```powershell
cd api/live-agent
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy config.example.env .env
python main.py
```

## Local Files

Create these locally only:

```text
api/live-agent/.env
api/live-agent/data/signal.json
api/live-agent/data/approval.json
```

Do not commit them.

## Required Secret Environment Variables

Coinbase Advanced Trade CDP-style credentials are expected as environment variables:

```env
COINBASE_API_KEY_NAME=
COINBASE_API_PRIVATE_KEY=
COINBASE_PORTFOLIO_ID=
```

Optional legacy placeholders are included but unused unless you adapt the adapter:

```env
COINBASE_API_KEY=
COINBASE_API_SECRET=
COINBASE_API_PASSPHRASE=
```

## Signal Input

Default signal path:

```text
data/signal.json
```

Expected format follows `live-decision-v2.html` / `agent-trade-tracker-v4.html` output:

```json
{
  "pair": "SOL-USD",
  "amount": 40,
  "decision": "READY_LONG",
  "best": {
    "tf": "15M",
    "side": "LONG",
    "clear": true,
    "entry": 100,
    "target": 101.2,
    "stop": 99.4,
    "score": 80,
    "pnl": { "actual": 0.22, "risk": -0.18 },
    "fee": { "entryFee": 0.004, "exitFee": 0.006 },
    "spreadSpike": 1.05,
    "hollowFactor": 0.9
  }
}
```

## What This Agent Does

1. Reads V2 signal JSON.
2. Accepts spot LONG only.
3. Rejects SHORT for spot mode.
4. Validates risk gates.
5. Checks manual approval when required.
6. Uses dry-run order adapter by default.
7. Logs every decision.
8. Stores state in SQLite.
9. Blocks duplicates.
10. Tracks open positions.

## Not Included Yet

Actual Coinbase order placement is scaffolded behind a hard safety gate. The adapter must be completed and tested with official Coinbase SDK/API docs before live mode.

## Recommended Coinbase Key Permissions

- Start read-only.
- Add trade permission only after dry-run logs are correct.
- Never enable withdrawals.
- Use a restricted portfolio.
- Use tiny balances while testing.

## Files

```text
api/live-agent/
  README.md
  requirements.txt
  config.example.env
  .gitignore
  main.py
  agent/
    __init__.py
    approval.py
    coinbase_adapter.py
    config.py
    logger.py
    models.py
    risk_engine.py
    signal_reader.py
    state_store.py
```
