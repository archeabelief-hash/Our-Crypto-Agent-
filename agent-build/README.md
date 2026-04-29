# Agent Build Workspace

This folder is separate from the working dashboard app.

Use this path for the next backend-style agent build so the current app stays stable:

```text
/agent-build/
```

Current stable dashboard files stay at the repo root:

```text
home.html
live-decision.html
manual-selected.html
agent.html
live-wave-test.html
agent-trade-tracker-v2.html
liquidity-pnl-engine.html
```

## Rule
Do not edit the root app while testing backend agent logic. Build and test here first.

## Secret Safety
Never upload keys, secrets, tokens, passphrases, seed phrases, or `.env` files to GitHub.

Use local machine secrets or deployment-provider secrets only.

Local-only file name:

```text
agent-build/.env
```

This file must stay ignored by Git.

## Start Mode
Start with paper/sandbox mode only.

The first agent should:

- Read current signal outputs.
- Record decisions.
- Simulate entries and exits.
- Track paper PnL.
- Store state locally.
- Log why each setup passed or failed.

## Required Safety Gates
Every candidate signal must pass:

- fresh data
- positive modeled PnL
- acceptable spread
- acceptable spoof risk
- liquidity check
- duplicate-trade check
- max-size check
- daily-loss check

## Suggested Structure

```text
agent-build/
  README.md
  .gitignore
  paper-agent/
    README.md
    config.example.env
    state/
    logs/
```

## Stable Baseline
Production app baseline:

```text
c84665e0085e4648ce3a86e316c85c55977ce03d
```
