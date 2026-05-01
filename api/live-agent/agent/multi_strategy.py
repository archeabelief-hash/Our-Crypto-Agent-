from agent.pattern_memory import score_pattern


STRATEGIES = {
    "NO_TRADE": {
        "enabled": False,
        "size_multiplier": 0.0,
        "score_bias": 99,
        "description": "Blocks low-edge or unsafe market conditions.",
    },
    "TREND_FOLLOW": {
        "enabled": True,
        "size_multiplier": 1.0,
        "score_bias": 0,
        "description": "Uses clean momentum plus sniper confirmation.",
    },
    "BREAKOUT_CONFIRM": {
        "enabled": True,
        "size_multiplier": 0.75,
        "score_bias": 3,
        "description": "Requires stronger score because breakout entries are more failure-prone.",
    },
    "MEAN_REVERSION_SCALP": {
        "enabled": True,
        "size_multiplier": 0.45,
        "score_bias": 8,
        "description": "Small defensive scalp only when spread is stable and momentum is not collapsing.",
    },
}


def _num(value, default=0.0):
    try:
        return float(value)
    except Exception:
        return default


def select_strategy(signal):
    best = signal.get("best", {}) or {}
    diagnostics = signal.get("diagnostics", {}) or {}
    sniper = best.get("sniper", {}) or diagnostics.get("sniper", {}) or {}
    pattern = score_pattern(signal)

    score = _num(best.get("score"), 0)
    sniper_quality = _num(sniper.get("quality", diagnostics.get("sniperQuality", 0.5)), 0.5)
    momentum = _num(best.get("imbalanceMomentum", diagnostics.get("momentum", 0)), 0)
    spread_spike = _num(best.get("spreadSpike", diagnostics.get("spreadSpike", 1)), 1)
    vol_ratio = _num(best.get("volRatio", diagnostics.get("volRatio", 1)), 1)
    hollow = _num(best.get("hollowFactor", 1), 1)
    tf = best.get("tf", "NA")

    if spread_spike >= 2 or hollow < 0.75 or sniper_quality < 0.45:
        selected = "NO_TRADE"
        reason = "unsafe_spread_liquidity_or_sniper"
    elif sniper_quality >= 0.72 and momentum > 3 and spread_spike < 1.25:
        selected = "TREND_FOLLOW"
        reason = "clean_trend_build"
    elif score >= 78 and momentum > 10 and sniper_quality >= 0.65 and spread_spike < 1.5:
        selected = "BREAKOUT_CONFIRM"
        reason = "strong_breakout_pressure"
    elif tf in ("1M", "5M") and -3 <= momentum <= 3 and spread_spike < 1.2 and vol_ratio < 1.4:
        selected = "MEAN_REVERSION_SCALP"
        reason = "stable_low_momentum_scalp"
    else:
        selected = "NO_TRADE"
        reason = "no_strategy_edge"

    strategy = dict(STRATEGIES[selected])

    if pattern.get("status") == "FAVORABLE_PATTERN" and selected != "NO_TRADE":
        strategy["size_multiplier"] = min(1.25, strategy["size_multiplier"] * pattern.get("size_bias", 1.0))
        strategy["score_bias"] = max(-8, strategy["score_bias"] + pattern.get("bias", 0))
    elif pattern.get("status") == "UNFAVORABLE_PATTERN":
        strategy["size_multiplier"] = max(0.25, strategy["size_multiplier"] * pattern.get("size_bias", 1.0))
        strategy["score_bias"] = min(18, strategy["score_bias"] + pattern.get("bias", 0))
        if selected == "MEAN_REVERSION_SCALP":
            selected = "NO_TRADE"
            strategy = dict(STRATEGIES[selected])
            reason = "unfavorable_scalp_pattern_blocked"

    return {
        "selected": selected,
        "reason": reason,
        "strategy": strategy,
        "pattern": pattern,
        "inputs": {
            "score": score,
            "sniper_quality": sniper_quality,
            "momentum": momentum,
            "spread_spike": spread_spike,
            "vol_ratio": vol_ratio,
            "hollow": hollow,
            "timeframe": tf,
        },
    }
