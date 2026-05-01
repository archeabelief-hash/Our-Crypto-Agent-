def detect_regime(signal):
    best = signal.get("best", {}) or {}
    diagnostics = signal.get("diagnostics", {}) or {}

    score = float(best.get("score", 0) or 0)
    spread_spike = float(best.get("spreadSpike", diagnostics.get("spreadSpike", 1)) or 1)
    momentum = float(best.get("imbalanceMomentum", diagnostics.get("momentum", 0)) or 0)
    vol_ratio = float(best.get("volRatio", diagnostics.get("volRatio", 1)) or 1)
    hollow = float(best.get("hollowFactor", 1) or 1)
    sniper = best.get("sniper", {}) or diagnostics.get("sniper", {}) or {}
    sniper_quality = float(sniper.get("quality", diagnostics.get("sniperQuality", 0.5)) or 0.5)

    if spread_spike >= 2:
        return {"allowed": False, "regime": "HALT_SPREAD_SPIKE", "size_multiplier": 0, "reason": "spread expanded"}
    if hollow < 0.75:
        return {"allowed": False, "regime": "HOLLOW_BOOK", "size_multiplier": 0, "reason": "thin top-of-book liquidity"}
    if sniper_quality < 0.48 or momentum < -3:
        return {"allowed": False, "regime": "CHOP_OR_EXHAUSTION", "size_multiplier": 0, "reason": "sniper/momentum weak"}
    if abs(momentum) < 2 and score < 72:
        return {"allowed": False, "regime": "LOW_EDGE_CHOP", "size_multiplier": 0, "reason": "low momentum chop"}
    if vol_ratio >= 1.8 and sniper_quality < 0.72:
        return {"allowed": False, "regime": "UNSTABLE_VOLATILITY", "size_multiplier": 0, "reason": "volatile without clean sniper edge"}
    if sniper_quality >= 0.72 and momentum > 3 and spread_spike < 1.25:
        return {"allowed": True, "regime": "CLEAN_TREND_BUILD", "size_multiplier": 1.0, "reason": "clean building flow"}
    if vol_ratio >= 1.4:
        return {"allowed": True, "regime": "HIGH_VOL_DEFENSIVE", "size_multiplier": 0.55, "reason": "high volatility defensive sizing"}

    return {"allowed": True, "regime": "NORMAL", "size_multiplier": 0.75, "reason": "normal conditions"}
