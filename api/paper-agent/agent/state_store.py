class StateStore:
    def __init__(self):
        self.trades = []
        self.pnl = 0

    def add_trade(self, trade):
        self.trades.append(trade)

    def total_loss(self):
        return self.pnl
