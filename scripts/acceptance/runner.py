"""使用标准库验证 Thread、Turn、Item 和幂等契约。"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from dataclasses import dataclass
from http.client import RemoteDisconnected
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class AcceptanceResult:
    """单次验收的稳定结果。"""

    checks: tuple[str, ...]
    scenarios: tuple[str, ...] = ()


def _request(
    base_url: str,
    method: str,
    path: str,
    user_id: str,
    body: object | None = None,
    extra_headers: dict[str, str] | None = None,
) -> tuple[int, dict]:
    payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    headers = {
        "Accept": "application/json",
        "X-User-Id": user_id,
        "Content-Type": "application/json",
    }
    if extra_headers:
        headers.update(extra_headers)
    request = Request(
        base_url.rstrip("/") + path,
        method=method,
        data=payload,
        headers=headers,
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


def _item_page_fingerprint(page: dict, label: str) -> tuple[tuple[str | None, int], ...]:
    items = page.get("items")
    if not isinstance(items, list):
        raise RuntimeError(f"{label} 未返回 Item 数组")
    after_sequence = page.get("afterSequence", 0)
    next_after_sequence = page.get("nextAfterSequence", after_sequence)
    has_more = page.get("hasMore", False)
    if (
        type(after_sequence) is not int
        or type(next_after_sequence) is not int
        or type(has_more) is not bool
        or after_sequence < 0
        or next_after_sequence < 0
    ):
        raise RuntimeError(f"{label} 游标不是整数")
    if next_after_sequence < after_sequence:
        raise RuntimeError(f"{label} nextAfterSequence 未严格前进")
    if not items and (next_after_sequence != after_sequence or has_more):
        raise RuntimeError(f"{label} 空页必须保持游标且结束分页")
    if items and has_more and next_after_sequence <= after_sequence:
        raise RuntimeError(f"{label} hasMore=true 但游标没有前进")
    previous_sequence = after_sequence
    fingerprint: list[tuple[str | None, int]] = []
    item_ids: set[str] = set()
    for item in items:
        if not isinstance(item, dict) or type(item.get("sequence")) is not int:
            raise RuntimeError(f"{label} 存在无效 Item 序列")
        sequence = item["sequence"]
        if sequence <= after_sequence or sequence <= previous_sequence:
            raise RuntimeError(f"{label} Item sequence 未严格前进")
        item_id = item.get("itemId")
        if not isinstance(item_id, str) or not item_id.strip():
            raise RuntimeError(f"{label} 存在空 Item ID")
        if item_id in item_ids:
            raise RuntimeError(f"{label} 存在重复 Item ID")
        item_ids.add(item_id)
        fingerprint.append((item_id, sequence))
        previous_sequence = sequence
    if items and next_after_sequence != previous_sequence:
        raise RuntimeError(f"{label} nextAfterSequence 与最后 Item 不一致")
    return tuple(fingerprint)


def _interaction_fingerprint(payload: dict, label: str) -> tuple[str, str]:
    interaction_id = payload.get("interactionId")
    interaction_type = payload.get("type")
    if not isinstance(interaction_id, str) or not interaction_id.strip():
        raise RuntimeError(f"{label} 缺少 interactionId")
    if interaction_type not in {"QUESTION_CARD", "WORKFLOW_CHECKPOINT"}:
        raise RuntimeError(f"{label} 返回未知交互类型")
    return interaction_id, interaction_type


def _run_agent_contract(base_url: str, user_id: str) -> tuple[str, ...]:
    checks: list[str] = []
    status, page = _request(base_url, "GET", "/api/agent/threads?page=0&size=20", user_id)
    if status != 200 or not isinstance(page.get("items"), list):
        raise RuntimeError(f"Thread 列表契约失败：HTTP {status}")
    checks.append("thread-list")

    status, thread = _request(base_url, "POST", "/api/agent/threads", user_id, {"title": "acceptance thread"})
    if status != 200 or not thread.get("threadId"):
        raise RuntimeError(f"Thread 创建契约失败：HTTP {status}")
    thread_id = thread["threadId"]
    checks.append("thread-create")

    status, items = _request(base_url, "GET", f"/api/agent/threads/{thread_id}/items?afterSequence=0&limit=200", user_id)
    if status != 200:
        raise RuntimeError(f"Item 恢复契约失败：HTTP {status}")
    first_page = _item_page_fingerprint(items, "Item 恢复")
    checks.append("item-recovery")

    status, interaction = _request(
        base_url, "GET", f"/api/agent/threads/{thread_id}/interaction", user_id
    )
    if status == 204:
        repeated_status, _ = _request(
            base_url, "GET", f"/api/agent/threads/{thread_id}/interaction", user_id
        )
        if repeated_status != 204:
            raise RuntimeError(f"空开放交互重读契约失败：HTTP {repeated_status}")
        checks.append("interaction-uniqueness")
    elif status == 200:
        if not isinstance(interaction, dict):
            raise RuntimeError("开放交互契约未返回对象")
        first_interaction = _interaction_fingerprint(interaction, "开放交互")
        repeated_status, repeated = _request(
            base_url, "GET", f"/api/agent/threads/{thread_id}/interaction", user_id
        )
        if repeated_status != 200 or not isinstance(repeated, dict):
            raise RuntimeError(f"开放交互重读契约失败：HTTP {repeated_status}")
        if _interaction_fingerprint(repeated, "开放交互重读") != first_interaction:
            raise RuntimeError("同一 Thread 重读返回了不同的开放交互")
        checks.append("interaction-uniqueness")
    else:
        raise RuntimeError(f"开放交互契约失败：HTTP {status}")

    status, refreshed = _request(
        base_url, "GET", f"/api/agent/threads/{thread_id}/items?afterSequence=0&limit=200", user_id
    )
    if status != 200 or _item_page_fingerprint(refreshed, "刷新恢复") != first_page:
        raise RuntimeError(f"刷新恢复契约失败：HTTP {status}")
    checks.append("refresh-recovery")

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

    status, execution = _request(
        base_url,
        "GET",
        f"/api/agent/turns/{accepted['turnId']}/execution",
        user_id,
    )
    if status != 200 or execution.get("turnId") != accepted["turnId"]:
        raise RuntimeError(f"执行轨迹回放契约失败：HTTP {status}")
    checks.append("execution-replay")
    return tuple(checks)


def _fixture_stats(base_url: str, user_id: str) -> dict[str, int]:
    status, payload = _request(base_url, "GET", "/_fixture/stats", user_id)
    if status != 200 or not isinstance(payload, dict):
        raise RuntimeError(f"订单夹具统计契约失败：HTTP {status}")
    values: dict[str, int] = {}
    for key in ("idempotencyRecords", "businessMutations", "injectedFailures"):
        value = payload.get(key)
        if not isinstance(value, int) or value < 0:
            raise RuntimeError(f"订单夹具统计缺少非负整数：{key}")
        values[key] = value
    return values


def _assert_action_response(status: int, payload: dict, label: str) -> None:
    if status != 200 or not isinstance(payload, dict):
        raise RuntimeError(f"{label} HTTP 契约失败：{status}")
    if not isinstance(payload.get("success"), bool) or not isinstance(payload.get("code"), str):
        raise RuntimeError(f"{label} 返回缺少受控 success/code")


def _assert_stat_delta(
    before: dict[str, int], after: dict[str, int], label: str, minimum_injected: int = 0
) -> None:
    record_delta = after["idempotencyRecords"] - before["idempotencyRecords"]
    mutation_delta = after["businessMutations"] - before["businessMutations"]
    injected_delta = after["injectedFailures"] - before["injectedFailures"]
    if record_delta != 1 or mutation_delta != 1 or injected_delta < minimum_injected:
        raise RuntimeError(
            f"{label} 统计不符合幂等边界：records={record_delta}, "
            f"mutations={mutation_delta}, injected={injected_delta}"
        )


def _run_order_service_scenarios(
    base_url: str,
    user_id: str,
    allow_destructive_delete: bool,
    require_expedite_retry: bool,
    expedite_max_attempts: int,
) -> tuple[str, ...]:
    if expedite_max_attempts < 1 or expedite_max_attempts > 20:
        raise RuntimeError("催发货最大尝试次数必须在 1 到 20 之间")
    scenarios: list[str] = []
    token = uuid.uuid4().hex[:12]

    status, logistics = _request(base_url, "GET", "/orders/ORDER-EXT-STALLED-001/logistics", user_id)
    if status != 200 or not isinstance(logistics, list) or len(logistics) < 2:
        raise RuntimeError(f"物流场景失败：HTTP {status}")
    if any(
        not isinstance(event, dict)
        or not isinstance(event.get("eventId"), str)
        or not event["eventId"].strip()
        for event in logistics
    ):
        raise RuntimeError("物流场景包含无效事件")
    event_ids = [event["eventId"] for event in logistics]
    if len(event_ids) != len(set(event_ids)):
        raise RuntimeError("物流场景返回了重复或无效事件")
    scenarios.append("logistics")

    refund_key = f"acceptance-{token}-refund"
    refund_before = _fixture_stats(base_url, user_id)
    refund_status, refund = _request(
        base_url,
        "POST",
        "/orders/ORDER-EXT-REFUND-001/refund",
        user_id,
        {"reason": "演示验收原因"},
        {"Idempotency-Key": refund_key},
    )
    _assert_action_response(refund_status, refund, "退款")
    replay_status, replay = _request(
        base_url,
        "POST",
        "/orders/ORDER-EXT-REFUND-001/refund",
        user_id,
        {"reason": "演示验收原因"},
        {"Idempotency-Key": refund_key},
    )
    _assert_action_response(replay_status, replay, "退款幂等重放")
    if replay != refund:
        raise RuntimeError("退款幂等重放没有返回同一响应")
    order_status, order = _request(base_url, "GET", "/orders/ORDER-EXT-REFUND-001", user_id)
    if order_status != 200 or order.get("status") != "REFUNDED":
        raise RuntimeError(f"退款结果查询失败：HTTP {order_status}")
    _assert_stat_delta(refund_before, _fixture_stats(base_url, user_id), "退款")
    scenarios.append("refund-idempotency")

    expedite_key = f"acceptance-{token}-expedite"
    expedite_before = _fixture_stats(base_url, user_id)
    retry_count = 0
    final_expedite: dict = {}
    for _attempt in range(expedite_max_attempts):
        expedite_status, expedite = _request(
            base_url,
            "POST",
            "/orders/ORDER-EXT-TODAY-001/expedite",
            user_id,
            {},
            {"Idempotency-Key": expedite_key},
        )
        _assert_action_response(expedite_status, expedite, "催发货")
        final_expedite = expedite
        if expedite.get("success"):
            break
        if not expedite.get("retryable"):
            raise RuntimeError(f"催发货返回不可重试失败：{expedite.get('code')}")
        retry_count += 1
    else:
        raise RuntimeError("催发货在最大尝试次数内未成功")
    if require_expedite_retry and retry_count == 0:
        raise RuntimeError("未观察到催发货临时失败，无法证明重试路径")
    replay_status, replay = _request(
        base_url,
        "POST",
        "/orders/ORDER-EXT-TODAY-001/expedite",
        user_id,
        {},
        {"Idempotency-Key": expedite_key},
    )
    _assert_action_response(replay_status, replay, "催发货幂等重放")
    if replay != final_expedite:
        raise RuntimeError("催发货幂等重放没有返回同一响应")
    order_status, order = _request(base_url, "GET", "/orders/ORDER-EXT-TODAY-001", user_id)
    if order_status != 200 or order.get("logisticsStatus") != "EXPEDITE_REQUESTED":
        raise RuntimeError(f"催发货结果查询失败：HTTP {order_status}")
    _assert_stat_delta(
        expedite_before,
        _fixture_stats(base_url, user_id),
        "催发货",
        minimum_injected=retry_count,
    )
    scenarios.append("expedite-retry")

    if not allow_destructive_delete:
        scenarios.append("delete-gated")
    else:
        delete_key = f"acceptance-{token}-delete"
        delete_before = _fixture_stats(base_url, user_id)
        delete_status, deleted = _request(
            base_url,
            "DELETE",
            "/orders/ORDER-EXT-DELIVERED-001",
            user_id,
            extra_headers={"Idempotency-Key": delete_key},
        )
        _assert_action_response(delete_status, deleted, "删除")
        replay_status, replay = _request(
            base_url,
            "DELETE",
            "/orders/ORDER-EXT-DELIVERED-001",
            user_id,
            extra_headers={"Idempotency-Key": delete_key},
        )
        _assert_action_response(replay_status, replay, "删除幂等重放")
        if replay != deleted:
            raise RuntimeError("删除幂等重放没有返回同一响应")
        order_status, _ = _request(base_url, "GET", "/orders/ORDER-EXT-DELIVERED-001", user_id)
        logistics_status, logistics = _request(
            base_url, "GET", "/orders/ORDER-EXT-DELIVERED-001/logistics", user_id
        )
        if order_status != 404 or logistics_status != 404 or logistics != []:
            raise RuntimeError("删除场景未同时清理订单和物流")
        _assert_stat_delta(delete_before, _fixture_stats(base_url, user_id), "删除")
        scenarios.append("delete-idempotency")
    return tuple(scenarios)


def run(
    base_url: str,
    user_id: str,
    order_service_url: str | None = None,
    *,
    allow_destructive_delete: bool = False,
    require_expedite_retry: bool = False,
    expedite_max_attempts: int = 8,
) -> AcceptanceResult:
    """运行 Agent API 基线，并可选运行独立订单夹具的四类业务场景。"""

    checks = _run_agent_contract(base_url, user_id)
    scenarios = () if order_service_url is None else _run_order_service_scenarios(
        order_service_url,
        user_id,
        allow_destructive_delete,
        require_expedite_retry,
        expedite_max_attempts,
    )
    return AcceptanceResult(checks, scenarios)


def main(arguments: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="运行 Commerce Guardian Agent API 验收")
    parser.add_argument("--base-url", default="http://127.0.0.1:8090")
    parser.add_argument(
        "--order-service-url",
        help="可选的 disposable order-service fixture 地址，用于运行物流/退款/催发货/删除场景",
    )
    parser.add_argument("--user-id", default="demo-user-1")
    parser.add_argument(
        "--allow-destructive-fixture-actions",
        action="store_true",
        help="允许在一次性订单夹具中执行删除场景；不会作用于 Agent API",
    )
    parser.add_argument(
        "--require-expedite-retry",
        action="store_true",
        help="要求催发货场景至少观察到一次可重试临时失败",
    )
    parser.add_argument("--expedite-max-attempts", type=int, default=8)
    args = parser.parse_args(arguments)
    try:
        result = run(
            args.base_url,
            args.user_id,
            args.order_service_url,
            allow_destructive_delete=args.allow_destructive_fixture_actions,
            require_expedite_retry=args.require_expedite_retry,
            expedite_max_attempts=args.expedite_max_attempts,
        )
    except RuntimeError as failure:
        print(f"验收失败：{failure}", file=sys.stderr)
        return 1
    labels = list(result.checks)
    if result.scenarios:
        labels.append("scenarios=" + ",".join(result.scenarios))
    print("Commerce Guardian Agent 验收通过：" + ", ".join(labels))
    return 0
