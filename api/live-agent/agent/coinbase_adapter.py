from agent.logger import log

def execute_order(signal, config):
    # HARD SAFETY: no real execution implemented yet
    if config["mode"] != "live" or not config["allow_live"]:
        return {"mode": "DRY_RUN", "signal": signal}

    # Placeholder for future Coinbase integration
    log("LIVE MODE BLOCKED: Adapter not fully implemented", signal)
    return {
        "status": "BLOCKED",
        "reason": "SAFE_MODE_NO_EXECUTION"
    }
