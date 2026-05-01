def enforce_htf_bias(signal):
    best = signal.get("best", {}) or {}
    htf = best.get("htf", {}) or signal.get("htf", {}) or {}

    trend = htf.get("trend") or best.get("htfTrend") or "neutral"
    direction = signal.get("direction") or "LONG"

    if trend == "down" and direction == "LONG":
        return {"allowed": False, "reason": "HTF_BEARISH_BLOCK"}
    if trend == "up" and direction == "SHORT":
        return {"allowed": False, "reason": "HTF_BULLISH_BLOCK"}

    return {"allowed": True, "reason": "HTF_OK"}
