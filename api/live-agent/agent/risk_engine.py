def validate_trade(signal, config, open_positions_count=0):
    if config["kill"]:
        return False, "KILL_SWITCH"

    if config.get("spot_only", True):
        best = signal.get("best", {})
        if best.get("side") != "LONG":
            return False, "SPOT_ONLY_LONG"

    pair = signal.get("pair")
    if config.get("allowed_pairs") and pair not in config.get("allowed_pairs"):
        return False, "PAIR_NOT_ALLOWED"

    if open_positions_count >= config.get("max_open_trades", 1):
        return False, "MAX_OPEN_TRADES_REACHED"

    amount = signal.get("amount", 0)
    if amount > config.get("max_trade", 100):
        return False, "EXCEEDS_MAX_TRADE"

    best = signal.get("best", {})

    if best.get("spreadSpike", 0) >= 2:
        return False, "SPREAD_SPIKE"

    if best.get("hollowFactor", 1) < 0.2:
        return False, "HOLLOW_BOOK"

    return True, "OK"
