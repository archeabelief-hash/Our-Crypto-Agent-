from datetime import datetime
from agent.logger import log

class PaperBroker:
    def __init__(self, state):
        self.state = state

    def process(self, signal):
        best = signal.get('best')
        trade = {
            'pair': signal.get('pair'),
            'entry': best.get('entry'),
            'target': best.get('target'),
            'stop': best.get('stop'),
            'openedAt': datetime.utcnow().isoformat(),
        }
        self.state.add_trade(trade)
        log(f"Paper trade opened: {trade}")
