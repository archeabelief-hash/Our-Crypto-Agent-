import json


def read_signal(path):
    try:
        with open(path, 'r') as f:
            return json.load(f)
    except Exception:
        return None
