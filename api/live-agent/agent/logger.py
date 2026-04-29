import datetime

def log(label, data=None):
    ts = datetime.datetime.utcnow().isoformat()
    print(f"[{ts}] {label}: {data}")
