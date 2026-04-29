import os
from dotenv import load_dotenv

load_dotenv()

class Settings:
    def __init__(self):
        self.trade_mode = os.getenv('TRADE_MODE', 'paper')
        self.max_trade_usd = float(os.getenv('MAX_TRADE_USD', 25))
        self.daily_loss_limit = float(os.getenv('DAILY_LOSS_LIMIT_USD', 10))
        self.require_manual = os.getenv('REQUIRE_MANUAL_APPROVAL', 'true').lower() == 'true'
        self.signal_path = os.getenv('SIGNAL_PATH', 'data/signal.json')
