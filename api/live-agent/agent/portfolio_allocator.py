def select_best_asset(signals):
    if not signals:
        return None

    def score(sig):
        best = sig.get("best", {}) or {}
        return float(best.get("score", 0)) * float(best.get("sniper", {}).get("quality", 0.5))

    sorted_signals = sorted(signals, key=score, reverse=True)
    return sorted_signals[0]
