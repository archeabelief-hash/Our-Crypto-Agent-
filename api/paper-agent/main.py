import time
from agent.config import Settings
from agent.signal_reader import read_signal
from agent.risk_engine import validate_signal
from agent.paper_broker import PaperBroker
from agent.state_store import StateStore
from agent.logger import log

settings = Settings()
state = StateStore()
broker = PaperBroker(state)


def run():
    log('Agent started in mode: ' + settings.trade_mode)
    while True:
        signal = read_signal(settings.signal_path)
        if not signal:
            time.sleep(2)
            continue

        if not validate_signal(signal, settings, state):
            log('Signal rejected by risk engine')
            time.sleep(2)
            continue

        broker.process(signal)
        time.sleep(2)


if __name__ == '__main__':
    run()
