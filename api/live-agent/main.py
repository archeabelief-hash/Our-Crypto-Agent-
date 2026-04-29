import time
from agent.config import load_config
from agent.signal_reader import read_signal
from agent.risk_engine import validate_trade
from agent.coinbase_adapter import execute_order
from agent.logger import log

config = load_config()

log("Agent started", config)

while True:
    try:
        signal = read_signal()
        if not signal:
            time.sleep(2)
            continue

        decision = signal.get("decision")
        best = signal.get("best", {})

        if decision != "READY_LONG" or not best.get("clear"):
            log("No valid trade", signal)
            time.sleep(2)
            continue

        valid, reason = validate_trade(signal, config)

        if not valid:
            log("Trade blocked", reason)
            time.sleep(2)
            continue

        result = execute_order(signal, config)
        log("Execution result", result)

    except Exception as e:
        log("ERROR", str(e))

    time.sleep(3)
