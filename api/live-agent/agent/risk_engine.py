def validate_trade(signal, config):
    if config["kill"]:
        return False, "KILL_SWITCH"

    amount = signal.get("amount", 0)

    if amount > config["max_trade"]:
        return False, "EXCEEDS_MAX_TRADE"

    best = signal.get("best", {})

    if best.get("spreadSpike", 0) >= 2:
        return False, "SPREAD_SPIKE"

    if best.get("hollowFactor", 1) < 0.2:
        return False, "HOLLOW_BOOK"

    return True, "OK"
