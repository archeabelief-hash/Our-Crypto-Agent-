import uuid


class PositionManager:
    def __init__(self):
        self.positions = {}

    def open_position(self, pair, base_size, entry_price, target, stop, source_signal):
        pid = str(uuid.uuid4())
        risk = max(0, entry_price - stop)
        reward = max(0, target - entry_price)
        tp1 = entry_price + reward * 0.50 if reward > 0 else target
        tp2 = entry_price + reward * 0.80 if reward > 0 else target

        self.positions[pid] = {
            "id": pid,
            "pair": pair,
            "base_size": base_size,
            "remaining_size": base_size,
            "entry_price": entry_price,
            "target": target,
            "stop": stop,
            "original_stop": stop,
            "breakeven_stop": entry_price,
            "risk_per_unit": risk,
            "tp1": tp1,
            "tp2": tp2,
            "scale_1_done": False,
            "scale_2_done": False,
            "runner_done": False,
            "realized_exits": [],
            "source": source_signal,
            "status": "OPEN",
        }
        return self.positions[pid]

    def record_scale_exit(self, pid, label, size, exit_price):
        pos = self.positions.get(pid)
        if not pos:
            return None
        size = min(float(size), float(pos.get("remaining_size", 0)))
        pos["remaining_size"] = max(0, float(pos.get("remaining_size", 0)) - size)
        pos["realized_exits"].append({
            "label": label,
            "size": size,
            "exit_price": exit_price,
        })
        if label == "TP1":
            pos["scale_1_done"] = True
            pos["stop"] = max(pos["stop"], pos["breakeven_stop"])
        elif label == "TP2":
            pos["scale_2_done"] = True
        elif label in ("TARGET", "STOP", "RUNNER"):
            pos["runner_done"] = True
            pos["status"] = "CLOSED"
            pos["exit_reason"] = label
            pos["exit_price"] = exit_price
        if pos["remaining_size"] <= 0:
            pos["status"] = "CLOSED"
        return pos

    def close_position(self, pid, reason, exit_price):
        pos = self.positions.get(pid)
        if not pos:
            return None
        pos["status"] = "CLOSED"
        pos["exit_reason"] = reason
        pos["exit_price"] = exit_price
        pos["remaining_size"] = 0
        return pos

    def has_open_pair(self, pair):
        return any(p for p in self.positions.values() if p["pair"] == pair and p["status"] == "OPEN")

    def list_open(self):
        return [p for p in self.positions.values() if p["status"] == "OPEN"]

    def open_count(self):
        return len(self.list_open())
