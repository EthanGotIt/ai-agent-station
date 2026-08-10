"""Run stable rule-routed smoke scenarios and render a Markdown report."""

from __future__ import annotations

import argparse
import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class Scenario:
    name: str
    message: str
    expected_status: str
    expected_workflow: str
    expected_operation: str
    expected_card: str | None
    answers: tuple[dict[str, str], ...] = ()


SCENARIOS = (
    Scenario("订单详情", "查询订单 ORDER-PAID-001", "COMPLETED", "order-inquiry", "QUERY", "order_overview"),
    Scenario("物流追踪", "订单 ORDER-SHIPPED-STALLED-001 物流到哪了", "COMPLETED", "order-inquiry", "TRACK", "logistics_timeline"),
    Scenario("履约诊断", "订单 ORDER-SHIPPED-STALLED-001 物流停滞怎么办", "COMPLETED", "order-inquiry", "DIAGNOSE", "order_diagnosis"),
    Scenario("退款信息收集", "订单 ORDER-PAID-001 退款", "WAITING_USER_INPUT", "after-sales-refund", "APPLY", None),
    Scenario(
        "选择订单后追踪物流", "帮我看看物流", "COMPLETED", "order-inquiry", "TRACK", "logistics_timeline",
        ({"orderId": "ORDER-SHIPPED-STALLED-001"},)
    ),
    Scenario(
        "诊断补充问题类型", "请诊断订单 ORDER-SHIPPED-STALLED-001", "COMPLETED", "order-inquiry", "DIAGNOSE",
        "order_diagnosis", ({"issueType": "LOGISTICS_STALLED"},)
    ),
    Scenario(
        "自动退款多阶段确认", "订单 ORDER-PAID-001 退款", "COMPLETED", "after-sales-refund", "APPLY",
        "after_sales_result", ({"refundReason": "NOT_RECEIVED"}, {"decision": "CONFIRM"})
    ),
    Scenario(
        "人工审核多阶段确认", "订单 ORDER-SHIPPED-STALLED-001 退款", "COMPLETED", "after-sales-refund", "APPLY",
        "after_sales_result", ({"refundReason": "NOT_RECEIVED"}, {"decision": "CONFIRM"})
    ),
    Scenario("售后状态查询", "查询订单 ORDER-PAID-001 的售后状态", "COMPLETED", "after-sales-refund", "QUERY_STATUS", "after_sales_status"),
)


def invoke(base_url: str, user_id: str, scenario: Scenario) -> dict[str, Any]:
    payload = json.dumps(
        {
            "requestId": f"evaluation-{uuid.uuid4()}",
            "sessionId": "evaluation-session",
            "message": scenario.message,
            "memory": {"generate": False, "use": False},
        }
    ).encode("utf-8")
    request = Request(
        f"{base_url.rstrip('/')}/api/v1/agent/chat",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json", "X-User-Id": user_id},
    )
    with urlopen(request, timeout=20) as response:  # noqa: S310 -- explicit local/operator URL
        result = json.loads(response.read().decode("utf-8"))
    for answers in scenario.answers:
        result = answer(base_url, user_id, result, answers)
    return result


def answer(
        base_url: str, user_id: str, response: dict[str, Any], answers: dict[str, str]
) -> dict[str, Any]:
    question = response.get("question") or {}
    workflow_run = response.get("workflowRun") or {}
    if response.get("status") != "WAITING_USER_INPUT" or not question or not workflow_run:
        raise ValueError("workflow did not return a question card before answer")
    payload = json.dumps({
        "requestId": f"evaluation-answer-{uuid.uuid4()}",
        "sessionId": workflow_run.get("sessionId") or "evaluation-session",
        "questionId": question["questionId"],
        "checkpointId": workflow_run["checkpointId"],
        "expectedVersion": workflow_run["version"],
        "answers": answers,
        "memory": {"generate": False, "use": False},
    }).encode("utf-8")
    request = Request(
        f"{base_url.rstrip('/')}/api/v1/agent/workflow-runs/{workflow_run['runId']}/answers",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json", "X-User-Id": user_id},
    )
    with urlopen(request, timeout=20) as http_response:  # noqa: S310 -- explicit local/operator URL
        return json.loads(http_response.read().decode("utf-8"))


def matches(scenario: Scenario, response: dict[str, Any]) -> tuple[bool, str]:
    if response.get("status") != scenario.expected_status:
        return False, f"status={response.get('status')}"
    if response.get("workflowId") != scenario.expected_workflow:
        return False, f"workflowId={response.get('workflowId')}"
    if response.get("operation") != scenario.expected_operation:
        return False, f"operation={response.get('operation')}"
    if scenario.expected_card and response.get("result", {}).get("cardType") != scenario.expected_card:
        return False, f"cardType={response.get('result', {}).get('cardType')}"
    return True, "ok"


def render(results: list[tuple[Scenario, bool, str]]) -> str:
    passed = sum(1 for _, success, _ in results if success)
    lines = [
        "# AI Agent Station 业务回归报告",
        "",
        f"生成时间：{datetime.now(timezone.utc).isoformat()}",
        "",
        f"结果：**{passed}/{len(results)}** 通过（仅规则可判定 HTTP 场景）。",
        "",
        "| 场景 | 预期 Workflow / 操作 | 结果 | 详情 |",
        "|---|---|---|---|",
    ]
    for scenario, success, detail in results:
        state = "PASS" if success else "FAIL"
        lines.append(
            f"| {scenario.name} | {scenario.expected_workflow} / {scenario.expected_operation} | {state} | {detail} |"
        )
    lines.extend(
        [
            "",
            "本报告不替代真实模型验收：ReAct 多工具选择、ASK 确认与模型结构化输出请运行 `scripts.live_acceptance`。",
        ]
    )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic AI Agent Station business scenarios")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--user-id", default="demo-user-1")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    results: list[tuple[Scenario, bool, str]] = []
    for scenario in SCENARIOS:
        try:
            response = invoke(args.base_url, args.user_id, scenario)
            success, detail = matches(scenario, response)
        except (HTTPError, URLError, TimeoutError, ValueError, json.JSONDecodeError) as error:
            success, detail = False, type(error).__name__
        results.append((scenario, success, detail))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(render(results), encoding="utf-8")
    return 0 if all(success for _, success, _ in results) else 1
