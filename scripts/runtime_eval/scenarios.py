"""Agent 质量评测的内部场景格式和固定场景集。"""

from __future__ import annotations

from dataclasses import dataclass
from types import MappingProxyType
from typing import Mapping


@dataclass(frozen=True)
class EvalScenario:
    """描述一个不进入 Core 或 HTTP DTO 的评测场景。"""

    id: str
    prompt: str
    setup: Mapping[str, object]
    expected_decision: str
    required_items: tuple[str, ...]
    forbidden_items: tuple[str, ...]
    max_open_interactions: int
    expected_mutation_count: int

    def __post_init__(self) -> None:
        if not self.id or not self.prompt:
            raise ValueError("场景必须包含 id 和 prompt")
        if self.max_open_interactions < 0 or self.expected_mutation_count < 0:
            raise ValueError("场景边界不能为负数")
        object.__setattr__(self, "setup", MappingProxyType(dict(self.setup)))

    def to_record(self) -> dict[str, object]:
        """以约定的 camelCase 键输出内部场景记录。"""

        return {
            "id": self.id,
            "prompt": self.prompt,
            "setup": dict(self.setup),
            "expectedDecision": self.expected_decision,
            "requiredItems": list(self.required_items),
            "forbiddenItems": list(self.forbidden_items),
            "maxOpenInteractions": self.max_open_interactions,
            "expectedMutationCount": self.expected_mutation_count,
        }


_READ_FORBIDDEN = ("QUESTION_CARD", "WORKFLOW_RUN", "EXTERNAL_ACTION_COMMAND")
_QUESTION_FORBIDDEN = ("EXTERNAL_ACTION_COMMAND", "WORKFLOW_RUN")
_REJECT_FORBIDDEN = ("EXTERNAL_ACTION_COMMAND",)
_WORKFLOW_ITEMS = ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS", "EXTERNAL_ACTION_COMMAND")


SCENARIOS: tuple[EvalScenario, ...] = (
    EvalScenario(
        "exact-order",
        "查询订单 ORDER-PAID-001 的详情",
        {"orderId": "ORDER-PAID-001"},
        "READ_TOOL",
        ("TOOL_RESULT", "ORDER_DETAIL"),
        _READ_FORBIDDEN,
        0,
        0,
    ),
    EvalScenario(
        "today-orders",
        "列出今天的订单",
        {"date": "today"},
        "READ_TOOL",
        ("TOOL_RESULT", "ORDER_LIST"),
        _READ_FORBIDDEN,
        0,
        0,
    ),
    EvalScenario(
        "stalled-logistics",
        "查找物流三天没有更新的订单",
        {"stalledDays": 3},
        "READ_TOOL",
        ("TOOL_RESULT", "ORDER_LIST", "LOGISTICS_TIMELINE"),
        _READ_FORBIDDEN,
        0,
        0,
    ),
    EvalScenario(
        "logistics-detail",
        "查看订单 ORDER-PAID-001 的物流详情",
        {"orderId": "ORDER-PAID-001"},
        "READ_TOOL",
        ("TOOL_RESULT", "LOGISTICS_TIMELINE"),
        _READ_FORBIDDEN,
        0,
        0,
    ),
    EvalScenario(
        "refund-missing-order",
        "帮我退款",
        {"action": "refund"},
        "ASK_USER",
        ("QUESTION_CARD",),
        _QUESTION_FORBIDDEN,
        1,
        0,
    ),
    EvalScenario(
        "refund-missing-reason",
        "请给订单 ORDER-PAID-001 退款",
        {"action": "refund", "orderId": "ORDER-PAID-001"},
        "ASK_USER",
        ("QUESTION_CARD",),
        _QUESTION_FORBIDDEN,
        1,
        0,
    ),
    EvalScenario(
        "refund-reject",
        "订单 ORDER-PAID-001 退款，我拒绝授权",
        {"action": "refund", "orderId": "ORDER-PAID-001", "answer": "REJECT"},
        "FINISH_WORKFLOW_REJECTED",
        ("WORKFLOW_RESULT",),
        _REJECT_FORBIDDEN,
        0,
        0,
    ),
    EvalScenario(
        "refund-approve",
        "订单 ORDER-PAID-001 退款，我批准授权",
        {"action": "refund", "orderId": "ORDER-PAID-001", "answer": "APPROVE"},
        "FINISH_WORKFLOW_APPROVED",
        _WORKFLOW_ITEMS,
        (),
        0,
        1,
    ),
    EvalScenario(
        "expedite-failure",
        "催发货订单 ORDER-PAID-001，外部服务暂时失败",
        {"action": "expedite", "orderId": "ORDER-PAID-001", "externalStatus": "TEMPORARY_FAILURE"},
        "FINISH_WORKFLOW_FAILED",
        _WORKFLOW_ITEMS,
        (),
        0,
        0,
    ),
    EvalScenario(
        "expedite-manual-retry",
        "催发货失败后人工重试订单 ORDER-PAID-001",
        {
            "action": "expedite",
            "orderId": "ORDER-PAID-001",
            "externalStatus": "MANUAL_RETRY",
        },
        "FINISH_WORKFLOW_MANUAL_RETRY",
        _WORKFLOW_ITEMS,
        (),
        0,
        1,
    ),
    EvalScenario(
        "delete-reject",
        "删除订单 ORDER-PAID-001，我拒绝授权",
        {"action": "delete", "orderId": "ORDER-PAID-001", "answer": "REJECT"},
        "FINISH_WORKFLOW_REJECTED",
        ("WORKFLOW_RESULT",),
        _REJECT_FORBIDDEN,
        0,
        0,
    ),
    EvalScenario(
        "delete-approve",
        "删除订单 ORDER-PAID-001，我批准授权",
        {"action": "delete", "orderId": "ORDER-PAID-001", "answer": "APPROVE"},
        "FINISH_WORKFLOW_APPROVED",
        _WORKFLOW_ITEMS,
        (),
        0,
        1,
    ),
)


EXPECTED_SCENARIO_IDS = tuple(scenario.id for scenario in SCENARIOS)


def validate_scenarios(scenarios: tuple[EvalScenario, ...] = SCENARIOS) -> None:
    """检查固定场景集没有重复 ID 或不符合边界的记录。"""

    if len(scenarios) != 12:
        raise ValueError(f"场景数量必须为 12，实际为 {len(scenarios)}")
    ids = [scenario.id for scenario in scenarios]
    if len(ids) != len(set(ids)):
        raise ValueError("场景 id 必须唯一")
    for scenario in scenarios:
        if scenario.expected_mutation_count > 1:
            raise ValueError(f"场景 {scenario.id} 的外部业务变更不允许超过一次")
