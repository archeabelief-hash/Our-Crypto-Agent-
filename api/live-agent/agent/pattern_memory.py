import json
from pathlib import Path

MEMORY_FILE = Path("data/pattern_memory.json")
MAX_PATTERNS = 300
MIN_SAMPLES = 5


def _read():
    try:
        if MEMORY_FILE.exists():
            return json.loads(MEMORY_FILE.read_text())
    except Exception:
        pass
    return {"patterns": {}}


def _write(data):
    MEMORY_FILE.parent.mkdir(parents=True, exist_ok=True)
    MEMORY_FILE.write_text(json.dumps(data, indent=2))


def _bucket(value, steps):
    try:
        value = float(value)
    except Exception:
        value = 0
    for label, lo, hi in steps:
        if lo <= value < hi:
            return label
    return steps[-1][0]


def fingerprint(signal):
    best = signal.get("best", {}) or {}
    diagnostics = signal.get("diagnostics", {}) or {}
    sniper = best.get("sniper", {}) or diagnostics.get("sniper", {}) or {}

    tf = best.get("tf", "NA")
    score = _bucket(best.get("score", 0), [("S0", 0, 60), ("S1", 60, 75), ("S2", 75, 90), ("S3", 90, 101)])
    sniper_q = _bucket(sniper.get("quality", diagnostics.get("sniperQuality", 0.5)), [("Q0", 0, 0.5), ("Q1", 0.5, 0.68), ("Q2", 0.68, 0.8), ("Q3", 0.8, 1.01)])
    momentum = _bucket(best.get("imbalanceMomentum", diagnostics.get("momentum", 0)), [("MNEG", -999, -3), ("MFLAT", -3, 3), ("MPOS", 3, 12), ("MSTR", 12, 999)])
    spread = _bucket(best.get("spreadSpike", diagnostics.get("spreadSpike", 1)), [("SP0", 0, 1.15), ("SP1", 1.15, 1.5), ("SP2", 1.5, 2), ("SP3", 2, 999)])
    vol = _bucket(best.get("volRatio", diagnostics.get("volRatio", 1)), [("VLOW", 0, 0.8), ("VNORM", 0.8, 1.4), ("VHIGH", 1.4, 1.8), ("VCHAOS", 1.8, 999)])

    return "|".join([tf, score, sniper_q, momentum, spread, vol])


def record_outcome(signal, pnl):
    data = _read()
    key = fingerprint(signal)
    p = data["patterns"].setdefault(key, {"wins": 0, "losses": 0, "pnl": 0.0, "samples": 0})
    pnl = float(pnl or 0)
    p["samples"] += 1
    p["pnl"] += pnl
    if pnl > 0:
        p["wins"] += 1
    else:
        p["losses"] += 1

    if len(data["patterns"]) > MAX_PATTERNS:
        ranked = sorted(data["patterns"].items(), key=lambda kv: kv[1].get("samples", 0), reverse=True)[:MAX_PATTERNS]
        data["patterns"] = dict(ranked)

    _write(data)
    return p


def score_pattern(signal):
    data = _read()
    key = fingerprint(signal)
    p = data.get("patterns", {}).get(key)
    if not p or p.get("samples", 0) < MIN_SAMPLES:
        return {"key": key, "status": "LEARNING", "bias": 0, "size_bias": 1.0, "samples": p.get("samples", 0) if p else 0}

    samples = p["samples"]
    winrate = p["wins"] / samples
    avg_pnl = p["pnl"] / samples

    bias = 0
    size_bias = 1.0
    status = "NEUTRAL"

    if winrate >= 0.62 and avg_pnl > 0:
        bias = -2
        size_bias = 1.08
        status = "FAVORABLE_PATTERN"
    elif winrate <= 0.45 or avg_pnl < 0:
        bias = 4
        size_bias = 0.82
        status = "UNFAVORABLE_PATTERN"

    return {
        "key": key,
        "status": status,
        "bias": bias,
        "size_bias": size_bias,
        "samples": samples,
        "winrate": round(winrate, 4),
        "avg_pnl": round(avg_pnl, 4),
    }
