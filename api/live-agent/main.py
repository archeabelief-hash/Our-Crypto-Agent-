import time
from agent.config import load_config
from agent.signal_reader import read_signal
from agent.risk_engine import validate_trade
from agent.execution_lifecycle import ExecutionLifecycle
from agent.logger import log

config = load_config()
engine = ExecutionLifecycle(config)

log("Agent started", config)

while True:
    try:
        signal = read_signal()
        if signal:
            decision = signal.get("decision")
            best = signal.get("best", {})

            if decision == "READY_LONG" and best.get("clear"):
                valid, reason = validate_trade(signal, config, engine.open_positions_count())

                if valid:
                    result = engine.process_signal(signal)
                    log("ENTRY RESULT", result)
                else:
                    log("Trade blocked", reason)

        exit_results = engine.manage_positions()
        for r in exit_results:
            log("EXIT RESULT", r)

    except Exception as e:
        log("ERROR", str(e))

    time.sleep(3)
