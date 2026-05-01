import time
from agent.config import load_config
from agent.signal_reader import read_signal
from agent.risk_engine import validate_trade
from agent.execution_lifecycle import ExecutionLifecycle
from agent.logger import log
from agent.strategy_tuner import apply_tuner
from agent.multi_strategy import select_strategy

base_config = load_config()
engine = ExecutionLifecycle(base_config)

log("Agent started", base_config)

while True:
    try:
        signal = read_signal()
        config = apply_tuner(base_config, signal)

        if signal:
            decision = signal.get("decision")
            best = signal.get("best", {})
            strategy_info = select_strategy(signal)

            if strategy_info.get("selected") == "NO_TRADE":
                log("STRATEGY_BLOCK", {
                    "strategy": strategy_info,
                    "tuner": config.get("tuner_state"),
                })
            elif decision == "READY_LONG" and best.get("clear"):
                strategy = strategy_info.get("strategy", {})
                tuned_config = dict(config)
                tuned_config["min_signal_score"] = float(tuned_config.get("min_signal_score", 60)) + float(strategy.get("score_bias", 0))
                tuned_config["max_trade"] = float(tuned_config.get("max_trade", 100)) * float(strategy.get("size_multiplier", 1.0))

                valid, reason = validate_trade(signal, tuned_config, engine.open_positions_count())

                if valid:
                    result = engine.process_signal(signal)
                    log("ENTRY RESULT", {
                        "result": result,
                        "tuner": tuned_config.get("tuner_state"),
                        "strategy": strategy_info,
                    })
                else:
                    log("Trade blocked", {
                        "reason": reason,
                        "tuner": tuned_config.get("tuner_state"),
                        "strategy": strategy_info,
                    })

        exit_results = engine.manage_positions()
        for r in exit_results:
            log("EXIT RESULT", r)

    except Exception as e:
        log("ERROR", str(e))

    time.sleep(3)
