import os
from dotenv import load_dotenv


def _bool(name, default="false"):
    return os.getenv(name, default).lower() == "true"


def _float(name, default):
    try:
        return float(os.getenv(name, default))
    except ValueError:
        return float(default)


def _int(name, default):
    try:
        return int(os.getenv(name, default))
    except ValueError:
        return int(default)


def load_config():
    load_dotenv()
    return {
        "mode": os.getenv("TRADE_MODE", "dry_run"),
        "allow_live": _bool("ALLOW_LIVE_ORDERS", "false"),
        "spot_only": _bool("SPOT_ONLY", "true"),
        "post_only": _bool("POST_ONLY", "true"),
        "kill": _bool("KILL_SWITCH", "false"),
        "require_manual": _bool("REQUIRE_MANUAL_APPROVAL", "true"),
        "max_trade": _float("MAX_TRADE_USD", 100),
        "max_open_trades": _int("MAX_OPEN_TRADES", 1),
        "order_timeout_seconds": _int("ORDER_TIMEOUT_SECONDS", 35),
        "cancel_on_timeout": _bool("CANCEL_ON_TIMEOUT", "true"),
        "max_reprices": _int("MAX_REPRICES", 1),
        "sniper_required": _bool("SNIPER_REQUIRED", "true"),
        "min_sniper_quality": _float("MIN_SNIPER_QUALITY", 0.68),
        "min_signal_score": _float("MIN_SIGNAL_SCORE", 60),
        "allowed_intervals": [p.strip() for p in os.getenv("ALLOWED_INTERVALS", "1M,5M,15M").split(",") if p.strip()],
        "allowed_pairs": [p.strip() for p in os.getenv("ALLOWED_PAIRS", "SOL-USD,BTC-USD,ETH-USD").split(",") if p.strip()],
        "key_name": os.getenv("COINBASE_API_KEY_NAME"),
        "private_key": os.getenv("COINBASE_API_PRIVATE_KEY"),
        "portfolio": os.getenv("COINBASE_PORTFOLIO_ID"),
    }
