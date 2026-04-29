import uuid


class PositionManager:
    def __init__(self):
        self.positions = {}

    def open_position(self, pair, base_size, entry_price, target, stop, source_signal):
        pid = str(uuid.uuid4())
        self.positions[pid] = {
            "id": pid,
            "pair": pair,
            "base_size": base_size,
            "entry_price": entry_price,
            "target": target,
            "stop": stop,
            "source": source_signal,
            "status": "OPEN",
        }
        return self.positions[pid]

    def close_position(self, pid, reason, exit_price):
        pos = self.positions.get(pid)
        if not pos:
            return None
        pos["status"] = "CLOSED"
        pos["exit_reason"] = reason
        pos["exit_price"] = exit_price
        return pos

    def has_open_pair(self, pair):
        return any(p for p in self.positions.values() if p["pair"] == pair and p["status"] == "OPEN")

    def list_open(self):
        return [p for p in self.positions.values() if p["status"] == "OPEN"]

    def open_count(self):
        return len(self.list_open())
