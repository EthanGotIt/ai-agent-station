"""使用标准库验证 v3 Thread、Turn、Item 和幂等契约。"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from http.client import RemoteDisconnected
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class AcceptanceResult:
    """单次验收的稳定结果。"""

    checks: tuple[str, ...]


def _request(base_url: str, method: str, path: str, user_id: str, body: object | None = None) -> tuple[int, dict]:
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = Request(
        base_url.rstrip("/") + path,
        method=method,
        data=payload,
        headers={"Accept": "application/json", "X-User-Id": user_id, "Content-Type": "application/json"},
    )
    try:
        with urlopen(request, timeout=15) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except HTTPError as failure:
        raw = failure.read().decode("utf-8")
        return failure.code, json.loads(raw) if raw else {}
    except (URLError, RemoteDisconnected) as failure:
        raise RuntimeError(f"无法连接 Agent API：{failure}") from failure


def run(base_url: str, user_id: str) -> AcceptanceResult:
    checks: list[str] = []
    status, page = _request(base_url, "GET", "/api/agent/threads?page=0&size=20", user_id)
    if status != 200 or not isinstance(page.get("items"), list):
        raise RuntimeError(f"Thread 列表契约失败：HTTP {status}")
    checks.append("thread-list")

    status, thread = _request(base_url, "POST", "/api/agent/threads", user_id, {"title": "v3 acceptance"})
    if status != 200 or not thread.get("threadId"):
        raise RuntimeError(f"Thread 创建契约失败：HTTP {status}")
    thread_id = thread["threadId"]
    checks.append("thread-create")

    status, items = _request(base_url, "GET", f"/api/agent/threads/{thread_id}/items?afterSequence=0&limit=200", user_id)
    if status != 200 or not isinstance(items.get("items"), list):
        raise RuntimeError(f"Item 恢复契约失败：HTTP {status}")
    checks.append("item-recovery")

    request_id = "acceptance-client-request"
    status, accepted = _request(
        base_url,
        "POST",
        f"/api/agent/threads/{thread_id}/turns",
        user_id,
        {"clientRequestId": request_id, "message": "查询订单 ORDER-PAID-001 的状态"},
    )
    if status != 202 or not accepted.get("turnId"):
        raise RuntimeError(f"Turn 入队契约失败：HTTP {status}")
    checks.append("turn-accepted")

    duplicate_status, duplicate = _request(
        base_url,
        "POST",
        f"/api/agent/threads/{thread_id}/turns",
        user_id,
        {"clientRequestId": request_id, "message": "查询订单 ORDER-PAID-001 的状态"},
    )
    if duplicate_status != 202 or duplicate.get("turnId") != accepted["turnId"]:
        raise RuntimeError("重复 clientRequestId 未返回同一 Turn")
    checks.append("turn-idempotency")
    return AcceptanceResult(tuple(checks))


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="运行 AI Agent Station v3 API 验收")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument("--user-id", default="demo-user-1")
    args = parser.parse_args(arguments)
    try:
        result = run(args.base_url, args.user_id)
    except RuntimeError as failure:
        print(f"验收失败：{failure}", file=sys.stderr)
        return 1
    print("v3 验收通过：" + ", ".join(result.checks))
    return 0
