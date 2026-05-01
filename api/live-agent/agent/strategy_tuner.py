import json
from pathlib import Path

PERFORMANCE_FILE = Path("data/performance.json")
TUNER_FILE = Path("data/strategy_tuner.json")

DEFAULT_STATE = {
    "min_signal_score_delta": 0,
    "min_sniper_quality_delta": 0,
    "size_multiplier": 1.0,
    "last_reason": "INIT",
    "sample_count": 0,
    "winrate": 0,
    "avg_pnl": 0,
    "max_drawdown": 0,
}


def _read_json(path, fallback):
    try:
        if path.exists():
            return json.loads(path.read_text())
    except Exception:
        pass
    return fallback.copy() if isinstance(fallback, dict) else fallback


def _write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2))


def _performance():
    data = _read_json(PERFORMANCE_FILE, {"trades": []})
    trades = data.get("trades", [])[-100:]
    if not trades:
        return {"count": 0, "winrate": 0, "avg_pnl": 0, "max_drawdown": 0}

    wins = [t for t in trades if float(t.get("pnl", 0) or 0) > 0]
    pnl_values = [float(t.get("pnl", 0) or 0) for t in trades]
    equity = 0
    peak = 0
    max_dd = 0
    for pnl in pnl_values:
        equity += pnl
        peak = max(peak, equity)
        max_dd = min(max_dd, equity - peak)

    return {
        "count": len(trades),
        "winrate": len(wins) / len(trades),
        "avg_pnl": sum(pnl_values) / len(pnl_values),
        "max_drawdown": max_dd,
    }


def update_tuner_state():
    perf = _performance()
    state = _read_json(TUNER_FILE, DEFAULT_STATE)

    if perf["count"] < 20:
        state.update({
            "last_reason": "INSUFFICIENT_DATA",
            "sample_count": perf["count"],
            "winrate": perf["winrate"],
            "avg_pnl": perf["avg_pnl"],
            "max_drawdown": perf["max_drawdown"],
        })
        _write_json(TUNER_FILE, state)
        return state

    score_delta = int(state.get("min_signal_score_delta", 0))
    sniper_delta = float(state.get("min_sniper_quality_delta", 0))
    size_multiplier = float(state.get("size_multiplier", 1.0))
    reason = "UNCHANGED"

    if perf["winrate"] < 0.48 or perf["avg_pnl"] < 0:
        score_delta += 2
        sniper_delta += 0.01
        size_multiplier *= 0.9
        reason = "TIGHTEN_LOW_EDGE"
    elif perf["winrate"] > 0.64 and perf["avg_pnl"] > 0:
        score_delta -= 1
        sniper_delta -= 0.005
        size_multiplier *= 1.05
        reason = "RELAX_STRONG_EDGE"

    if perf["max_drawdown"] < -10:
        score_delta += 2
        sniper_delta += 0.01
        size_multiplier *= 0.85
        reason = "TIGHTEN_DRAWDOWN"

    state.update({
        "min_signal_score_delta": max(-8, min(18, score_delta)),
        "min_sniper_quality_delta": max(-0.03, min(0.08, sniper_delta)),
        "size_multiplier": round(max(0.35, min(1.25, size_multiplier)), 4),
        "last_reason": reason,
        "sample_count": perf["count"],
        "winrate": round(perf["winrate"], 4),
        "avg_pnl": round(perf["avg_pnl"], 4),
        "max_drawdown": round(perf["max_drawdown"], 4),
    })
    _write_json(TUNER_FILE, state)
    return state


def apply_tuner(config):
    state = update_tuner_state()
    tuned = dict(config)
    tuned["min_signal_score"] = max(50, min(90, float(config.get("min_signal_score", 60)) + state.get("min_signal_score_delta", 0)))
    tuned["min_sniper_quality"] = max(0.5, min(0.85, float(config.get("min_sniper_quality", 0.68)) + state.get("min_sniper_quality_delta", 0)))
    tuned["max_trade"] = max(1, float(config.get("max_trade", 100)) * state.get("size_multiplier", 1.0))
    tuned["tuner_state"] = state
    return tuned
