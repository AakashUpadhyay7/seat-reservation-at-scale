#!/usr/bin/env python3
"""
Concurrent hot-seat and mixed-seat burst tester.
Requires Python 3.10+ and stdlib only.
Usage:
  python scripts/burst.py http://localhost:8080 <SHOW_ID>
"""
import concurrent.futures, json, sys, time, uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

BASE = sys.argv[1].rstrip("/")
SHOW_ID = sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 500
HOT_SEAT = "A1"

def call(i):
    user = f"burst-user-{i}"
    token = f"Bearer user:{user}:local-secret"
    key = str(uuid.uuid4())
    req = Request(
        f"{BASE}/shows/{SHOW_ID}/reserve",
        data=json.dumps({"seats":[HOT_SEAT]}).encode(),
        headers={
            "Content-Type":"application/json",
            "Authorization":token,
            "Idempotency-Key":key,
        },
        method="POST")
    try:
        with urlopen(req, timeout=30) as r:
            return r.status, "confirmed"
    except HTTPError as e:
        body = e.read().decode(errors="replace")
        try:
            code = json.loads(body).get("code", "http")
        except Exception:
            code = "http"
        return e.code, code
    except (URLError, TimeoutError) as e:
        return 0, "transport-error"

start = time.time()
with concurrent.futures.ThreadPoolExecutor(max_workers=min(100, N)) as ex:
    results = list(ex.map(call, range(N)))

counts = {}
for status, reason in results:
    key = f"{status}:{reason}"
    counts[key] = counts.get(key, 0) + 1

print(json.dumps({
    "requests": N,
    "elapsed_seconds": round(time.time()-start, 2),
    "outcomes": counts
}, indent=2))

with urlopen(f"{BASE}/shows/{SHOW_ID}", timeout=10) as r:
    show = json.load(r)

print(json.dumps({
    "reconciliation": {
        "total": show["total_seats"],
        "available": show["available"],
        "held": show["held"],
        "confirmed": show["confirmed"],
        "sum": show["available"] + show["held"] + show["confirmed"],
        "invariant_ok": (
            show["available"] + show["held"] + show["confirmed"]
            == show["total_seats"]
        )
    }
}, indent=2))
