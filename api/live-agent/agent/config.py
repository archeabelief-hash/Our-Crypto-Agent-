import os
from dotenv import load_dotenv

def load_config():
    load_dotenv()
    return {
        "mode": os.getenv("TRADE_MODE", "dry_run"),
        "allow_live": os.getenv("ALLOW_LIVE_ORDERS", "false").lower() == "true",
        "kill": os.getenv("KILL_SWITCH", "false").lower() == "true",
        "max_trade": float(os.getenv("MAX_TRADE_USD", 100)),
        "key_name": os.getenv("COINBASE_API_KEY_NAME"),
        "private_key": os.getenv("COINBASE_API_PRIVATE_KEY"),
        "portfolio": os.getenv("COINBASE_PORTFOLIO_ID")
    }
