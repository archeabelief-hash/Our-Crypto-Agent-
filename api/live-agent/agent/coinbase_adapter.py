from agent.logger import log


class CoinbaseAdapter:
    def __init__(self, config):
        self.config = config

    def _safe(self):
        return self.config["mode"] != "live" or not self.config["allow_live"]

    def place_post_only_limit_buy(self, product_id, base_size, limit_price, client_order_id):
        if self._safe():
            return {
                "mode": "DRY_RUN",
                "side": "BUY",
                "product_id": product_id,
                "base_size": base_size,
                "limit_price": limit_price,
                "client_order_id": client_order_id,
                "filled": True
            }

        # LIVE EXECUTION (requires Coinbase SDK)
        log("LIVE BUY ORDER", {
            "pair": product_id,
            "size": base_size,
            "price": limit_price
        })
        return {"status": "LIVE_NOT_CONNECTED"}

    def place_post_only_limit_sell(self, product_id, base_size, limit_price, client_order_id):
        if self._safe():
            return {
                "mode": "DRY_RUN",
                "side": "SELL",
                "product_id": product_id,
                "base_size": base_size,
                "limit_price": limit_price,
                "client_order_id": client_order_id,
                "filled": True
            }

        log("LIVE SELL ORDER", {
            "pair": product_id,
            "size": base_size,
            "price": limit_price
        })
        return {"status": "LIVE_NOT_CONNECTED"}

    def cancel_order(self, order_id):
        return {"cancelled": True, "order_id": order_id}

    def get_order(self, order_id):
        return {"filled": True, "average_price": 0}

    def get_quote(self, pair):
        return {"bid": 0}
