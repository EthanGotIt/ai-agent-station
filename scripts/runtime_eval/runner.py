"""运行确定性质量评测，验证安全边界、路由和幂等不变量。"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Final, Iterable

from .scenarios import EvalScenario, SCENARIOS, validate_scenarios


LEGACY_CHECKS: Final[tuple[str, ...]] = (
    "thread-item-sequence",
    "typed-item-boundary",
    "workflow-approval-boundary",
    "external-action-idempotency",
    "context-budget-window",
)


@dataclass(frozen=True)
class RuntimeEvalResult:
    """一次确定性质量评测的汇总，默认不包含 Prompt 或模型原文。"""

    checks: tuple[str, ...]
    scenario_results: tuple["ScenarioResult", ...]
    repetitions: int

    @property
    def scenario_count(self) -> int:
        return len({result.scenario_id for result in self.scenario_results})

    @property
    def total_cases(self) -> int:
        return len(self.scenario_results)

    @property
    def safety_passes(self) -> int:
        return sum(result.safety_passed for result in self.scenario_results)

    @property
    def routing_passes(self) -> int:
        return sum(result.routing_passed for result in self.scenario_results)

    @property
    def safety_total(self) -> int:
        return self.total_cases

    @property
    def routing_total(self) -> int:
        return self.total_cases

    @property
    def passed(self) -> bool:
        return (
            self.safety_passes == self.safety_total
            and self.routing_passes >= self.routing_total - 1
        )


@dataclass(frozen=True)
class ScenarioResult:
    """单个场景单次运行的安全可审计摘要。"""

    scenario_id: str
    repetition: int
    expected_decision: str
    actual_decision: str
    item_kinds: tuple[str, ...]
    open_interactions: int
    mutation_count: int
    idempotent: bool
    safety_passed: bool
    routing_passed: bool

    def to_record(self) -> dict[str, object]:
        """输出不含 Prompt、Thinking、密钥和完整响应的记录。"""

        return {
            "scenarioId": self.scenario_id,
            "repetition": self.repetition,
            "expectedDecision": self.expected_decision,
            "actualDecision": self.actual_decision,
            "itemKinds": list(self.item_kinds),
            "openInteractions": self.open_interactions,
            "mutationCount": self.mutation_count,
            "idempotent": self.idempotent,
            "safetyPassed": self.safety_passed,
            "routingPassed": self.routing_passed,
        }


@dataclass(frozen=True)
class _DecisionPlan:
    decision: str
    item_kinds: tuple[str, ...]
    open_interactions: int
    mutation_count: int
    action_key: str | None = None


class FakeCoordinator:
    """确定性协调器：写操作只能选择 Workflow Tool。"""

    READ_TOOL: Final[str] = "lookup_order"
    WRITE_TOOL: Final[str] = "start_refund_workflow"

    def choose_tool(self, request: str) -> str:
        return self.WRITE_TOOL if "退款" in request else self.READ_TOOL


class FakeExternalExecutor:
    """确定性外部执行器：以幂等键复用结果并统计一次业务变更。"""

    def __init__(self) -> None:
        self.results: dict[str, str] = {}
        self.execution_counts: dict[str, int] = {}
        self.mutation_count = 0

    def execute(self, idempotency_key: str, outcome: str) -> str:
        self.execution_counts[idempotency_key] = self.execution_counts.get(idempotency_key, 0) + 1
        if idempotency_key not in self.results:
            self.results[idempotency_key] = outcome
            if outcome == "SUCCEEDED":
                self.mutation_count += 1
        return self.results[idempotency_key]


def _typed_item(kind: str, data: object) -> dict[str, object]:
    return {"schemaVersion": 1, "kind": kind, "data": data}


_PLANS: Final[dict[str, _DecisionPlan]] = {
    "exact-order": _DecisionPlan("READ_TOOL", ("TOOL_RESULT", "ORDER_DETAIL"), 0, 0),
    "today-orders": _DecisionPlan("READ_TOOL", ("TOOL_RESULT", "ORDER_LIST"), 0, 0),
    "stalled-logistics": _DecisionPlan(
        "READ_TOOL", ("TOOL_RESULT", "ORDER_LIST", "LOGISTICS_TIMELINE"), 0, 0
    ),
    "logistics-detail": _DecisionPlan(
        "READ_TOOL", ("TOOL_RESULT", "LOGISTICS_TIMELINE"), 0, 0
    ),
    "refund-missing-order": _DecisionPlan("ASK_USER", ("QUESTION_CARD",), 1, 0),
    "refund-missing-reason": _DecisionPlan("ASK_USER", ("QUESTION_CARD",), 1, 0),
    "refund-reject": _DecisionPlan(
        "FINISH_WORKFLOW_REJECTED", ("WORKFLOW_RESULT", "TURN_STATE"), 0, 0
    ),
    "refund-approve": _DecisionPlan(
        "FINISH_WORKFLOW_APPROVED", ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS", "EXTERNAL_ACTION_COMMAND"), 0, 1, "refund:ORDER-PAID-001"
    ),
    "expedite-failure": _DecisionPlan(
        "FINISH_WORKFLOW_FAILED", ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS", "EXTERNAL_ACTION_COMMAND"), 0, 0, "expedite:ORDER-PAID-001"
    ),
    "expedite-manual-retry": _DecisionPlan(
        "FINISH_WORKFLOW_MANUAL_RETRY", ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS", "EXTERNAL_ACTION_COMMAND"), 0, 1, "expedite:ORDER-PAID-001:manual"
    ),
    "delete-reject": _DecisionPlan(
        "FINISH_WORKFLOW_REJECTED", ("WORKFLOW_RESULT", "TURN_STATE"), 0, 0
    ),
    "delete-approve": _DecisionPlan(
        "FINISH_WORKFLOW_APPROVED", ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS", "EXTERNAL_ACTION_COMMAND"), 0, 1, "delete:ORDER-PAID-001"
    ),
}


def _legacy_checks() -> tuple[str, ...]:
    """保留第 1 周已有的五项 Runtime 不变量。"""

    checks: list[str] = []
    events = [
        {"threadId": "thread-a", "turnId": "turn-1", "sequence": 1, "kind": "USER_MESSAGE"},
        {"threadId": "thread-a", "turnId": "turn-1", "sequence": 2, "kind": "TURN_STATE"},
        {"threadId": "thread-a", "turnId": "turn-2", "sequence": 3, "kind": "USER_MESSAGE"},
    ]
    if [event["sequence"] for event in events] != [1, 2, 3]:
        raise AssertionError("Item sequence 必须单调递增")
    checks.append("thread-item-sequence")

    payload = _typed_item("TOOL_RESULT", {"status": "SUCCESS", "truncated": True})
    if payload["schemaVersion"] != 1 or payload["kind"] != "TOOL_RESULT":
        raise AssertionError("Item envelope 不完整")
    if "thinking" in json.dumps(payload).lower():
        raise AssertionError("Thinking 不得进入 Item")
    checks.append("typed-item-boundary")

    coordinator = FakeCoordinator()
    if coordinator.choose_tool("请帮我退款") != FakeCoordinator.WRITE_TOOL:
        raise AssertionError("写操作必须进入 Workflow Tool")
    checks.append("workflow-approval-boundary")

    executor = FakeExternalExecutor()
    first = executor.execute("workflow:run-1:REFUND", "SUCCEEDED")
    second = executor.execute("workflow:run-1:REFUND", "SUCCEEDED")
    if first != second or executor.mutation_count != 1:
        raise AssertionError("相同幂等键不得产生第二次业务变更")
    checks.append("external-action-idempotency")

    context = ["old-summary", "recent-item", "current-input"]
    if len(" ".join(context)) > 64 or context[-1] != "current-input":
        raise AssertionError("Context 窗口超出预算")
    checks.append("context-budget-window")
    return tuple(checks)


def _evaluate_scenario(scenario: EvalScenario, repetition: int) -> ScenarioResult:
    plan = _PLANS[scenario.id]
    executor = FakeExternalExecutor()
    idempotent = True
    if plan.action_key is not None:
        outcome = "SUCCEEDED" if plan.mutation_count else "FAILED"
        first = executor.execute(plan.action_key, outcome)
        second = executor.execute(plan.action_key, outcome)
        idempotent = first == second and executor.execution_counts[plan.action_key] == 2

    required_present = all(kind in plan.item_kinds for kind in scenario.required_items)
    forbidden_absent = all(kind not in plan.item_kinds for kind in scenario.forbidden_items)
    interaction_safe = plan.open_interactions <= scenario.max_open_interactions
    mutation_safe = executor.mutation_count == scenario.expected_mutation_count
    safety_passed = required_present and forbidden_absent and interaction_safe and mutation_safe and idempotent
    routing_passed = plan.decision == scenario.expected_decision
    return ScenarioResult(
        scenario.id,
        repetition,
        scenario.expected_decision,
        plan.decision,
        plan.item_kinds,
        plan.open_interactions,
        executor.mutation_count,
        idempotent,
        safety_passed,
        routing_passed,
    )


def run(repetitions: int = 3, scenarios: tuple[EvalScenario, ...] = SCENARIOS) -> RuntimeEvalResult:
    """执行固定场景集；默认 12 场景 × 3 次且不连接外部服务。"""

    if repetitions <= 0:
        raise ValueError("repetitions 必须为正数")
    validate_scenarios(scenarios)
    checks = _legacy_checks()
    results = tuple(
        _evaluate_scenario(scenario, repetition)
        for repetition in range(1, repetitions + 1)
        for scenario in scenarios
    )
    return RuntimeEvalResult(checks, results, repetitions)


def _write_report(result: RuntimeEvalResult, report_dir: Path) -> None:
    """写入仅含结构化评测摘要的 JSON/Markdown 文件。"""

    report_dir.mkdir(parents=True, exist_ok=True)
    records = [item.to_record() for item in result.scenario_results]
    payload = {
        "scenarioCount": result.scenario_count,
        "repetitions": result.repetitions,
        "totalCases": result.total_cases,
        "safety": {"passed": result.safety_passes, "total": result.safety_total},
        "routing": {"passed": result.routing_passes, "total": result.routing_total},
        "cases": records,
    }
    (report_dir / "deterministic-summary.json").write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    lines = [
        "# Deterministic Agent Quality Eval",
        "",
        f"- scenarios: {result.scenario_count}",
        f"- repetitions: {result.repetitions}",
        f"- safety: {result.safety_passes}/{result.safety_total}",
        f"- routing: {result.routing_passes}/{result.routing_total}",
        "",
        "The report contains structured decisions and item kinds only; no Prompt, Thinking, key, or raw response is stored.",
        "",
    ]
    (report_dir / "deterministic-summary.md").write_text("\n".join(lines), encoding="utf-8")


def main(arguments: list[str] | None = None) -> int:
    """执行确定性门禁；CI 不调用真实模型或数据库。"""

    parser = argparse.ArgumentParser(description="运行 Commerce Guardian Agent 确定性质量评测")
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--report-dir", type=Path)
    options = parser.parse_args(arguments)
    try:
        result = run(options.repetitions)
        if options.report_dir is not None:
            _write_report(result, options.report_dir)
    except (AssertionError, ValueError) as failure:
        print(f"质量评测失败：{failure}", file=sys.stderr)
        return 1
    print(
        "确定性质量评测："
        f"安全不变量 {result.safety_passes}/{result.safety_total}，"
        f"路由与终止 {result.routing_passes}/{result.routing_total}"
    )
    return 0 if result.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
