import time
from agent.coinbase_adapter import CoinbaseAdapter
from agent.logger import log
from agent.position_manager import PositionManager
from agent.capital_allocator import allocate_capital


class ExecutionLifecycle:
    def __init__(self, config):
        self.config = config
        self.adapter = CoinbaseAdapter(config)
        self.positions = PositionManager()

    def manage_positions(self):
        results = []
        for position in self.positions.list_open():
            quote = self.adapter.get_quote(position["pair"])
            bid = float(quote.get("bid", 0))
            if bid <= 0:
                continue

            # TP1
            if not position["scale_1_done"] and bid >= position["tp1"]:
                size = position["base_size"] * 0.40
                self._scale(position, "TP1", size, bid)
                continue

            # TP2
            if position["scale_1_done"] and not position["scale_2_done"] and bid >= position["tp2"]:
                size = position["base_size"] * 0.30
                self._scale(position, "TP2", size, bid)
                continue

            # Final target
            if bid >= position["target"]:
                self._scale(position, "TARGET", position["remaining_size"], bid)
                continue

            # Stop
            if bid <= position["stop"]:
                self._scale(position, "STOP", position["remaining_size"], bid)

        return results

    def _scale(self, position, label, size, price):
        order = self.adapter.place_post_only_limit_sell(
            product_id=position["pair"],
            base_size=size,
            limit_price=price,
            client_order_id=f"exit-{position['id']}-{label}"
        )
        log("SCALE_EXIT", {"label": label, "price": price, "size": size})
        self.positions.record_scale_exit(position["id"], label, size, price)
