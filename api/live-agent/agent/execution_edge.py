def analyze_execution_edge(signal):
    best = signal.get("best", {}) or {}
    diagnostics = signal.get("diagnostics", {}) or {}

    spread_spike = _num(best.get("spreadSpike", diagnostics.get("spreadSpike", 1)), 1)
    hollow = _num(best.get("hollowFactor", 1), 1)
    momentum = _num(best.get("imbalanceMomentum", diagnostics.get("momentum", 0)), 0)
    sniper = best.get("sniper", {}) or diagnostics.get("sniper", {}) or {}
    sniper_quality = _num(sniper.get("quality", diagnostics.get("sniperQuality", 0.5)), 0.5)
    projected = _num(best.get("projectedPct", best.get("movePct", 0)), 0)
    required = _num(best.get("requiredPct", 0), 0)

    queue_penalty = 0
    if spread_spike > 1.25:
        queue_penalty += 0.18
    if hollow < 0.85:
        queue_penalty += 0.18
    if momentum < 0:
        queue_penalty += 0.12

    raw_fill_probability = 0.62 + (sniper_quality - 0.5) * 0.55 - queue_penalty
    fill_probability = max(0, min(1, raw_fill_probability))

    expected_edge = projected - required
    if fill_probability < 0.45:
        verdict = "LOW_FILL_PROBABILITY"
        allowed = False
    elif expected_edge <= 0:
        verdict = "NO_EXECUTION_EDGE"
        allowed = False
    elif spread_spike >= 2:
        verdict = "SPREAD_UNSAFE"
        allowed = False
    else:
        verdict = "EXECUTION_EDGE_OK"
        allowed = True

    return {
        "allowed": allowed,
        "verdict": verdict,
        "fill_probability": round(fill_probability, 4),
        "expected_edge_pct": round(expected_edge, 4),
        "queue_penalty": round(queue_penalty, 4),
        "spread_spike": spread_spike,
        "hollow_factor": hollow,
    }


def _num(value, default=0.0):
    try:
        return float(value)
    except Exception:
        return default
