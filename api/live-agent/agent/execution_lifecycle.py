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

    def open_positions_count(self):
        return self.positions.open_count()

    def process_signal(self, signal):
        allocation = allocate_capital(signal, self.config)
        if not allocation.get("allowed"):
            return {"status": "BLOCKED", "reason": allocation.get("reason"), "allocation": allocation}

        best = signal.get("best", {})
        pair = signal.get("pair")
        amount = float(allocation.get("amount", 0))
        entry = self._refined_entry(float(best.get("entry", 0)), signal)
        target = float(best.get("target", 0))
        stop = float(best.get("stop", 0))

        if not pair or entry <= 0 or target <= 0 or stop <= 0 or amount <= 0:
            return {"status": "BLOCKED", "reason": "BAD_SIGNAL_VALUES"}

        if self.positions.has_open_pair(pair):
            return {"status": "BLOCKED", "reason": "PAIR_ALREADY_OPEN"}

        chunk_count = max(1, min(5, int(self.config.get("twap_chunks", 3))))
        chunk_amount = amount / chunk_count
        filled_size = 0.0
        filled_notional = 0.0
        fills = []

        for chunk_index in range(chunk_count):
            result = self._submit_entry_chunk(pair, best, entry, chunk_amount, chunk_index)
            fills.append(result)
            if result.get("filled"):
                size = float(result.get("base_size", 0))
                price = float(result.get("average_price", entry))
                filled_size += size
                filled_notional += size * price

            if chunk_index < chunk_count - 1:
                time.sleep(max(1, int(self.config.get("twap_delay_seconds", 4))))

        if filled_size <= 0:
            return {"status": "UNFILLED", "fills": fills, "allocation": allocation}

        target_size = amount / entry
        fill_ratio = filled_size / target_size if target_size else 0
        min_ratio = float(self.config.get("min_partial_fill_ratio", 0.35))
        if fill_ratio < min_ratio:
            return {
                "status": "PARTIAL_TOO_SMALL",
                "fill_ratio": fill_ratio,
                "filled_size": filled_size,
                "fills": fills,
                "allocation": allocation,
            }

        avg_entry = filled_notional / filled_size
        position = self.positions.open_position(
            pair=pair,
            base_size=filled_size,
            entry_price=avg_entry,
            target=target,
            stop=stop,
            source_signal={
                **signal,
                "allocation": allocation,
                "refined_entry": entry,
                "fills": fills,
                "fill_ratio": fill_ratio,
            },
        )
        return {"status": "POSITION_OPEN", "position": position, "allocation": allocation, "fills": fills}

    def _submit_entry_chunk(self, pair, best, entry, chunk_amount, chunk_index):
        timeout = int(self.config.get("order_timeout_seconds", 35))
        max_reprices = int(self.config.get("max_reprices", 1))
        base_size = chunk_amount / entry

        for attempt in range(max_reprices + 1):
            limit_price = self._entry_price(entry, attempt)
            order = self.adapter.place_post_only_limit_buy(
                product_id=pair,
                base_size=base_size,
                limit_price=limit_price,
                client_order_id=self._client_id(pair, best, f"{chunk_index}-{attempt}"),
            )
            log("ENTRY_CHUNK_SUBMITTED", {"chunk": chunk_index, "attempt": attempt, "order": order})

            if order.get("error"):
                return {"filled": False, "error": order.get("error"), "chunk": chunk_index}

            filled = self._wait_for_fill(order, timeout)
            if filled.get("filled"):
                avg = float(filled.get("average_price") or limit_price)
                return {
                    "filled": True,
                    "chunk": chunk_index,
                    "base_size": base_size,
                    "average_price": avg,
                    "order": order,
                }

            cancel_result = self.adapter.cancel_order(order.get("order_id"))
            log("ENTRY_CHUNK_CANCELLED", {"chunk": chunk_index, "cancel": cancel_result})

        return {"filled": False, "chunk": chunk_index}

    def manage_positions(self):
        results = []
        for position in self.positions.list_open():
            quote = self.adapter.get_quote(position["pair"])
            bid = float(quote.get("bid", 0))
            if bid <= 0:
                continue

            self._update_trailing_state(position, bid)

            if not position.get("scale_1_done") and bid >= position["tp1"]:
                results.append(self._scale(position, "TP1", position["base_size"] * 0.40, bid))
                continue

            if position.get("scale_1_done") and not position.get("scale_2_done") and bid >= position["tp2"]:
                results.append(self._scale(position, "TP2", position["base_size"] * 0.30, bid))
                continue

            if position.get("scale_2_done") and bid <= position.get("trailing_stop", position["stop"]):
                results.append(self._scale(position, "TRAILING_STOP", position["remaining_size"], bid))
                continue

            if bid >= position["target"]:
                results.append(self._scale(position, "TARGET", position["remaining_size"], bid))
                continue

            if bid <= position["stop"]:
                results.append(self._scale(position, "STOP", position["remaining_size"], bid))

        return [r for r in results if r]

    def _update_trailing_state(self, position, bid):
        position["high_watermark"] = max(float(position.get("high_watermark", position["entry_price"])), bid)

        entry = float(position["entry_price"])
        target = float(position["target"])
        original_stop = float(position.get("original_stop", position["stop"]))
        move = max(target - entry, entry * 0.001)
        vol_ratio = float(position.get("source", {}).get("diagnostics", {}).get("volRatio", 1) or 1)

        # Volatility-aware trailing distance: wider in high volatility, tighter in low/chop.
        trail_fraction = 0.28
        if vol_ratio >= 1.8:
            trail_fraction = 0.40
        elif vol_ratio <= 0.8:
            trail_fraction = 0.22

        trailing_distance = move * trail_fraction
        proposed = position["high_watermark"] - trailing_distance

        if position.get("scale_1_done"):
            proposed = max(proposed, entry)
        else:
            proposed = max(proposed, original_stop)

        position["trailing_stop"] = max(float(position.get("trailing_stop", position["stop"])), proposed)

        if position.get("scale_1_done"):
            position["stop"] = max(float(position["stop"]), entry)
        if position.get("scale_2_done"):
            position["stop"] = max(float(position["stop"]), position["trailing_stop"])

    def _scale(self, position, label, size, price):
        size = max(0, min(float(size), float(position.get("remaining_size", 0))))
        if size <= 0:
            return None

        order = self.adapter.place_post_only_limit_sell(
            product_id=position["pair"],
            base_size=size,
            limit_price=price,
            client_order_id=f"exit-{position['id']}-{label.lower()}",
        )
        log("SCALE_EXIT_SUBMITTED", {"label": label, "price": price, "size": size, "order": order})

        filled = self._wait_for_fill(order, int(self.config.get("order_timeout_seconds", 35)))
        if filled.get("filled"):
            avg = float(filled.get("average_price") or price)
            updated = self.positions.record_scale_exit(position["id"], label, size, avg)
            return {"status": f"{label}_FILLED", "position": updated}

        cancel_result = self.adapter.cancel_order(order.get("order_id"))
        return {"status": f"{label}_CANCELLED_UNFILLED", "cancel": cancel_result}

    def _wait_for_fill(self, order, timeout):
        order_id = order.get("order_id")
        if order.get("mode") == "DRY_RUN":
            return {"filled": True, "average_price": float(order.get("limit_price", 0)), "dry_run": True}

        start = time.time()
        while time.time() - start < timeout:
            status = self.adapter.get_order(order_id)
            if status.get("filled"):
                return status
            time.sleep(2)
        return {"filled": False, "order_id": order_id}

    def _refined_entry(self, entry, signal):
        diagnostics = signal.get("diagnostics", {})
        best = signal.get("best", {})
        acceleration = float(diagnostics.get("acceleration", 0) or 0)
        imbalance = float(diagnostics.get("imbalance", diagnostics.get("im", 0)) or 0)
        sniper_quality = float(best.get("sniper", {}).get("quality", diagnostics.get("sniperQuality", 0.5)) or 0.5)

        if sniper_quality < 0.68:
            return round(entry * 0.9997, 8)
        if acceleration < 0:
            return round(entry * 0.9998, 8)
        if imbalance > 20 and acceleration > 0:
            return round(entry * 1.0001, 8)
        return round(entry, 8)

    def _entry_price(self, entry, attempt):
        return round(entry * (1 + attempt * 0.0002), 8)

    def _client_id(self, pair, best, suffix):
        tf = best.get("tf", "TF")
        return f"entry-{pair}-{tf}-{int(time.time())}-{suffix}"
