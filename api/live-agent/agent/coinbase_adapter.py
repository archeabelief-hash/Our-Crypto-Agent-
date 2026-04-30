from agent.logger import log

try:
    from coinbase.rest import RESTClient
except ImportError:
    RESTClient = None


class CoinbaseAdapter:
    def __init__(self, config):
        self.config = config
        self.client = None

        if not self._safe() and RESTClient:
            self.client = RESTClient(
                api_key=config.get("key_name"),
                api_secret=config.get("private_key")
            )

    def _safe(self):
        return self.config["mode"] != "live" or not self.config["allow_live"]

    def place_post_only_limit_buy(self, product_id, base_size, limit_price, client_order_id):
        if self._safe() or not self.client:
            return {
                "mode": "DRY_RUN",
                "side": "BUY",
                "product_id": product_id,
                "base_size": base_size,
                "limit_price": limit_price,
                "client_order_id": client_order_id,
                "filled": True
            }

        try:
            order = self.client.limit_order_gtc_buy(
                product_id=product_id,
                base_size=str(base_size),
                limit_price=str(limit_price),
                post_only=True,
                client_order_id=client_order_id
            )
            log("LIVE BUY ORDER", order)
            return {
                "order_id": order.get("order_id"),
                "status": "submitted"
            }
        except Exception as e:
            log("BUY_ERROR", str(e))
            return {"error": str(e)}

    def place_post_only_limit_sell(self, product_id, base_size, limit_price, client_order_id):
        if self._safe() or not self.client:
            return {
                "mode": "DRY_RUN",
                "side": "SELL",
                "product_id": product_id,
                "base_size": base_size,
                "limit_price": limit_price,
                "client_order_id": client_order_id,
                "filled": True
            }

        try:
            order = self.client.limit_order_gtc_sell(
                product_id=product_id,
                base_size=str(base_size),
                limit_price=str(limit_price),
                post_only=True,
                client_order_id=client_order_id
            )
            log("LIVE SELL ORDER", order)
            return {
                "order_id": order.get("order_id"),
                "status": "submitted"
            }
        except Exception as e:
            log("SELL_ERROR", str(e))
            return {"error": str(e)}

    def cancel_order(self, order_id):
        if self._safe() or not self.client:
            return {"cancelled": True, "order_id": order_id}

        try:
            return self.client.cancel_orders(order_ids=[order_id])
        except Exception as e:
            return {"error": str(e)}

    def get_order(self, order_id):
        if self._safe() or not self.client:
            return {"filled": True, "average_price": 0}

        try:
            order = self.client.get_order(order_id=order_id)
            return {
                "filled": float(order.get("filled_size", 0)) > 0,
                "average_price": float(order.get("average_filled_price", 0))
            }
        except Exception as e:
            return {"error": str(e)}

    def get_quote(self, pair):
        if not self.client:
            return {"bid": 0}

        try:
            book = self.client.get_best_bid_ask(product_id=pair)
            return {"bid": float(book.get("bid", 0))}
        except Exception:
            return {"bid": 0}
