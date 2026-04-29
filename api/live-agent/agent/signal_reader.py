import json
import os

PATH = "data/signal.json"

def read_signal():
    if not os.path.exists(PATH):
        return None
    try:
        with open(PATH, "r") as f:
            return json.load(f)
    except:
        return None
