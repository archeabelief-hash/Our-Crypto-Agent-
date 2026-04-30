import json
from pathlib import Path

DATA = Path("data/performance.json")


def _load():
    if not DATA.exists():
        return {"trades": []}
    try:
        return json.loads(DATA.read_text())
    except Exception:
        return {"trades": []}


def _save(d):
    DATA.parent.mkdir(parents=True, exist_ok=True)
    DATA.write_text(json.dumps(d, indent=2))


def record_trade(result):
    data = _load()
    data["trades"].append(result)
    data["trades"] = data["trades"][-500:]
    _save(data)


def stats():
    data = _load()
    trades = data.get("trades", [])
    if not trades:
        return {"winrate": 0, "avg": 0, "count": 0}
    wins = [t for t in trades if t.get("pnl", 0) > 0]
    winrate = len(wins) / len(trades)
    avg = sum(t.get("pnl", 0) for t in trades) / len(trades)
    return {"winrate": round(winrate, 3), "avg": round(avg, 2), "count": len(trades)}


def suggest_thresholds(config):
    s = stats()
    if s["count"] < 20:
        return {"status": "INSUFFICIENT_DATA"}

    new_score = config.get("min_signal_score", 60)
    if s["winrate"] < 0.5:
        new_score += 5
    elif s["winrate"] > 0.65:
        new_score -= 3

    return {
        "status": "ADJUSTED",
        "winrate": s["winrate"],
        "avg_pnl": s["avg"],
        "new_min_signal_score": max(50, min(90, new_score))
    }
