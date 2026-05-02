import json
from datetime import datetime, timezone
from pathlib import Path

STATE_FILE = Path("data/risk_state.json")
PERFORMANCE_FILE = Path("data/performance.json")

DEFAULT_STATE = {
    "date": "",
    "daily_pnl": 0.0,
    "daily_trades": 0,
    "loss_streak": 0,
    "volatility_halts": 0,
    "kill_switch": False,
    "last_reason": "INIT",
}


def _today():
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def _read(path, fallback):
    try:
        if path.exists():
            return json.loads(path.read_text())
    except Exception:
        pass
    return dict(fallback)


def _write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2))


def _state():
    state = _read(STATE_FILE, DEFAULT_STATE)
    if state.get("date") != _today():
        state = dict(DEFAULT_STATE)
        state["date"] = _today()
        _write(STATE_FILE, state)
    return state


def record_result(pnl):
    state = _state()
    pnl = float(pnl or 0)
    state["daily_pnl"] = float(state.get("daily_pnl", 0)) + pnl
    state["daily_trades"] = int(state.get("daily_trades", 0)) + 1
    state["loss_streak"] = int(state.get("loss_streak", 0)) + 1 if pnl < 0 else 0
    _write(STATE_FILE, state)
    return state


def validate_advanced_risk(signal, config):
    state = _state()
    best = signal.get("best", {}) or {}
    diagnostics = signal.get("diagnostics", {}) or {}

    if state.get("kill_switch"):
        return False, {"reason": "RISK_KILL_SWITCH", "state": state}

    max_daily_loss = float(config.get("max_daily_loss", 25))
    max_loss_streak = int(config.get("max_loss_streak", 3))
    max_daily_trades = int(config.get("max_daily_trades", 25))
    max_spread_spike = float(config.get("max_spread_spike", 2.0))
    max_vol_ratio = float(config.get("max_vol_ratio", 2.5))

    if float(state.get("daily_pnl", 0)) <= -abs(max_daily_loss):
        state["kill_switch"] = True
        state["last_reason"] = "MAX_DAILY_LOSS"
        _write(STATE_FILE, state)
        return False, {"reason": "MAX_DAILY_LOSS", "state": state}

    if int(state.get("loss_streak", 0)) >= max_loss_streak:
        return False, {"reason": "LOSS_STREAK", "state": state}

    if int(state.get("daily_trades", 0)) >= max_daily_trades:
        return False, {"reason": "MAX_DAILY_TRADES", "state": state}

    spread_spike = float(best.get("spreadSpike", diagnostics.get("spreadSpike", 1)) or 1)
    vol_ratio = float(best.get("volRatio", diagnostics.get("volRatio", 1)) or 1)

    if spread_spike >= max_spread_spike:
        return False, {"reason": "VOLATILITY_CIRCUIT_SPREAD", "spread_spike": spread_spike, "state": state}

    if vol_ratio >= max_vol_ratio:
        return False, {"reason": "VOLATILITY_CIRCUIT_VOL", "vol_ratio": vol_ratio, "state": state}

    return True, {"reason": "ADVANCED_RISK_OK", "state": state}
