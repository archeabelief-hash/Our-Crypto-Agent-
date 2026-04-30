def allocate_capital(signal, config):
    best = signal.get("best", {})
    diagnostics = signal.get("diagnostics", {})

    max_trade = float(config.get("max_trade", 100))
    score = float(best.get("score", 0) or 0)
    net = 0.0
    try:
        net = float(best.get("pnl", {}).get("actual", 0) or best.get("net", 0) or 0)
    except Exception:
        net = 0.0

    sniper = best.get("sniper", {}) or diagnostics.get("sniper", {}) or {}
    sniper_quality = float(sniper.get("quality", diagnostics.get("sniperQuality", 0.5)) or 0.5)
    spread_spike = float(best.get("spreadSpike", diagnostics.get("spreadSpike", 1)) or 1)
    hollow = float(best.get("hollowFactor", 1) or 1)
    tf = best.get("tf")

    if score < float(config.get("min_signal_score", 60)):
        return {"allowed": False, "amount": 0, "reason": "LOW_SCORE"}
    if config.get("sniper_required", True) and sniper_quality < float(config.get("min_sniper_quality", 0.68)):
        return {"allowed": False, "amount": 0, "reason": "LOW_SNIPER_QUALITY"}
    if spread_spike >= 2:
        return {"allowed": False, "amount": 0, "reason": "SPREAD_SPIKE"}
    if hollow < 0.75:
        return {"allowed": False, "amount": 0, "reason": "HOLLOW_BOOK"}
    if net <= 0:
        return {"allowed": False, "amount": 0, "reason": "NEGATIVE_EXPECTED_NET"}

    multiplier = 0.35
    if score >= 70:
        multiplier = 0.50
    if score >= 80 and sniper_quality >= 0.72:
        multiplier = 0.75
    if score >= 90 and sniper_quality >= 0.80 and tf in ("5M", "15M"):
        multiplier = 1.0

    amount = round(max_trade * multiplier, 2)
    return {
        "allowed": True,
        "amount": amount,
        "max_trade": max_trade,
        "multiplier": multiplier,
        "reason": "ALLOCATED",
        "score": score,
        "sniper_quality": sniper_quality,
        "expected_net": net,
        "timeframe": tf,
    }
