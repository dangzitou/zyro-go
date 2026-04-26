import argparse
import json
import statistics
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed


def request_chat(base_url: str, token: str, message: str, timeout: float):
    url = base_url.rstrip("/") + "/ai/agent/chat"
    payload = json.dumps({"message": message}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={
            "authorization": token,
            "Content-Type": "application/json; charset=utf-8",
        },
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", errors="replace")
            latency_ms = (time.perf_counter() - started) * 1000
            parsed = json.loads(body)
            success = bool(parsed.get("success"))
            answer = (((parsed.get("data") or {}).get("answer")) or "")[:120]
            return {
                "ok": success,
                "status": resp.status,
                "latency_ms": latency_ms,
                "answer": answer,
                "error": "",
            }
    except urllib.error.HTTPError as exc:
        latency_ms = (time.perf_counter() - started) * 1000
        body = exc.read().decode("utf-8", errors="replace")
        return {"ok": False, "status": exc.code, "latency_ms": latency_ms, "answer": "", "error": body[:200]}
    except Exception as exc:  # noqa: BLE001
        latency_ms = (time.perf_counter() - started) * 1000
        return {"ok": False, "status": 0, "latency_ms": latency_ms, "answer": "", "error": str(exc)}


def percentile(values, ratio: float):
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round((len(ordered) - 1) * ratio))))
    return ordered[index]


def main():
    parser = argparse.ArgumentParser(description="Load test Zyro Agent chat endpoint.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8081", help="Base URL of the backend")
    parser.add_argument("--token", required=True, help="Login token")
    parser.add_argument("--message", default="推荐我附近的咖啡店", help="Agent request message")
    parser.add_argument("--requests", type=int, default=50, help="Total request count")
    parser.add_argument("--concurrency", type=int, default=10, help="Concurrent workers")
    parser.add_argument("--timeout", type=float, default=20.0, help="Per request timeout seconds")
    args = parser.parse_args()

    started = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as pool:
        futures = [
            pool.submit(request_chat, args.base_url, args.token, args.message, args.timeout)
            for _ in range(max(1, args.requests))
        ]
        for future in as_completed(futures):
            results.append(future.result())
    elapsed_s = time.perf_counter() - started

    latencies = [item["latency_ms"] for item in results]
    success_count = sum(1 for item in results if item["ok"])
    failure_items = [item for item in results if not item["ok"]]

    report = {
        "requests": len(results),
        "concurrency": args.concurrency,
        "success_count": success_count,
        "failure_count": len(results) - success_count,
        "success_rate": round(success_count / len(results), 4) if results else 0.0,
        "elapsed_seconds": round(elapsed_s, 3),
        "throughput_rps": round(len(results) / elapsed_s, 2) if elapsed_s > 0 else 0.0,
        "latency_ms": {
            "avg": round(statistics.fmean(latencies), 2) if latencies else 0.0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0.0,
        },
        "sample_answer": next((item["answer"] for item in results if item["answer"]), ""),
        "sample_error": failure_items[0]["error"] if failure_items else "",
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
