# API Workspace

This folder is reserved for backend/API work that must stay separate from the stable static dashboard.

## Purpose
Use this path for server-side agent services, paper-trading APIs, signal ingestion endpoints, state persistence, and future backend integrations.

## Important
GitHub Pages cannot securely run private API-key logic from browser files. Any API-key based workflow must run on a backend/server environment, not inside public HTML or browser JavaScript.

## Do Not Commit
Never commit:

```text
.env
API keys
private keys
secrets
tokens
logs/
state files
SQLite runtime databases
```

## Suggested Structure

```text
api/
  README.md
  paper-agent/
  signal-service/
  risk-engine/
  state/
  logs/
```

## Relationship to App
Stable dashboard remains at repo root:

```text
home.html
live-decision.html
manual-selected.html
agent.html
live-wave-test.html
agent-trade-tracker-v2.html
liquidity-pnl-engine.html
```

Development rule:

```text
Build backend/API logic here first. Do not modify stable dashboard files unless the API build is already tested.
```
