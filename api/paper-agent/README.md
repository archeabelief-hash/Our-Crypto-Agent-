# Paper Agent Backend Scaffold

This folder is the isolated backend agent build area. It does not modify the working static dashboard.

## Current Mode
Default mode is paper only.

```text
TRADE_MODE=paper
```

The live adapter is intentionally disabled unless you explicitly configure a server environment later. Do not place secrets in GitHub.

## Local Setup

```bash
cd api/paper-agent
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp config.example.env .env
python main.py
```

On Windows PowerShell:

```powershell
cd api/paper-agent
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy config.example.env .env
python main.py
```

## Secret Rules

Never commit `.env`, API keys, passphrases, private keys, logs, or runtime state.

Use local `.env` or deployment-provider secrets only.

## Expected Signal Input

The agent expects a local JSON file matching the Live Decision / tracker format:

```json
{
  "pair": "SOL-USD",
  "amount": 40,
  "decision": "READY_LONG",
  "best": {
    "tf": "15M",
    "side": "LONG",
    "clear": true,
    "entry": 100.0,
    "target": 101.2,
    "stop": 99.3,
    "score": 78,
    "pnl": { "actual": 0.32, "risk": -0.24 },
    "fee": { "entryFee": 0.004, "exitFee": 0.006 }
  },
  "allIntervals": []
}
```

Default path:

```text
data/signal.json
```

## What It Does

- Reads signal JSON.
- Accepts spot LONG only.
- Rejects SHORT for spot mode.
- Applies risk gates.
- Simulates entry and exit in paper mode.
- Stores state in SQLite.
- Writes forensic logs locally.
- Keeps live adapter disabled by default.

## Folder Map

```text
api/paper-agent/
  main.py
  requirements.txt
  config.example.env
  .gitignore
  data/.gitkeep
  logs/.gitkeep
  state/.gitkeep
  agent/
    config.py
    models.py
    signal_reader.py
    risk_engine.py
    state_store.py
    paper_broker.py
    coinbase_adapter.py
    logger.py
```
