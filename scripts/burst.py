#!/usr/bin/env python3
"""Concurrency smoke test for the seat-reservation API. Python 3.10+, stdlib only.

Usage:
  python scripts/burst.py http://localhost:8080 SHOW_ID --mode hot-seat --requests 500
  python scripts/burst.py http://localhost:8080 SHOW_ID --mode idempotency --requests 100
  python scripts/burst.py http://localhost:8080 SHOW_ID --mode per-user --requests 20

Environment:
  AUTH_SECRET=local-secret   (change for a deployed service)
"""
import argparse, concurrent.futures, json, os, sys, time, uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


def http_json(url, *, method="GET", body=None, headers=None, timeout=30):
    req = Request(url, data=None if body is None else json.dumps(body).encode(),
                  headers=headers or {}, method=method)
    try:
        with urlopen(req, timeout=timeout) as r:
            raw = r.read().decode(errors="replace")
            return r.status, json.loads(raw) if raw else None
    except HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            data = json.loads(raw)
        except Exception:
            data = {"code": "http"}
        return e.code, data
    except (URLError, TimeoutError) as e:
        return 0, {"code": "transport-error", "message": str(e)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base_url")
    parser.add_argument("show_id")
    parser.add_argument("--mode", choices=["hot-seat", "idempotency", "per-user"], default="hot-seat")
    parser.add_argument("--requests", type=int, default=500)
    parser.add_argument("--seat", default="A1")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    secret = os.getenv("AUTH_SECRET", "local-secret")
    shared_key = f"burst-{uuid.uuid4()}"

    def call(i):
        user = "limit-user" if args.mode == "per-user" else f"burst-user-{i}"
        key = shared_key if args.mode == "idempotency" else f"burst-{uuid.uuid4()}"
        token = f"Bearer user:{user}:{secret}"
        if args.mode == "per-user":
            seat = f"P{i+1}"
        else:
            seat = args.seat
        return http_json(
            f"{base}/shows/{args.show_id}/reserve",
            method="POST",
            body={"seats": [seat]},
            headers={"Content-Type": "application/json", "Authorization": token,
                     "Idempotency-Key": key})

    start = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(200, args.requests)) as ex:
        results = list(ex.map(call, range(args.requests)))

    counts = {}
    for status, data in results:
        reason = (data or {}).get("code", "confirmed" if status == 201 else "unknown")
        k = f"{status}:{reason}"
        counts[k] = counts.get(k, 0) + 1

    status, show = http_json(f"{base}/shows/{args.show_id}")
    if status != 200:
        print(json.dumps({"error": "show-state-fetch-failed", "status": status, "data": show}, indent=2))
        sys.exit(2)

    total = show["total_seats"]
    available = show["available"]
    held = show["held"]
    confirmed = show["confirmed"]
    invariant = available + held + confirmed == total

    print(json.dumps({
        "mode": args.mode,
        "requests": args.requests,
        "elapsed_seconds": round(time.time() - start, 2),
        "outcomes": counts,
        "reconciliation": {
            "total": total, "available": available, "held": held,
            "confirmed": confirmed, "sum": available + held + confirmed,
            "invariant_ok": invariant
        }
    }, indent=2))

    if not invariant or any(status >= 500 for status, _ in results):
        sys.exit(1)


if __name__ == "__main__":
    main()
