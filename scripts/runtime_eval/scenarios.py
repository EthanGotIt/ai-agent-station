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


@dataclass(frozen=True)
class LongEvalScenario:
    """描述需要多个普通 Turn 才能验证的上下文场景。"""

    id: str
    prompts: tuple[str, ...]
    setup: Mapping[str, object]
    expected_decision: str
    required_items: tuple[str, ...]
    forbidden_items: tuple[str, ...]
    max_open_interactions: int
    assertion: str

    def __post_init__(self) -> None:
        if not self.id or len(self.prompts) < 2 or any(not prompt.strip() for prompt in self.prompts):
            raise ValueError("长对话场景至少需要两个非空 Turn")
        if self.max_open_interactions < 0 or not self.assertion.strip():
            raise ValueError("长对话场景边界不合法")
        object.__setattr__(self, "setup", MappingProxyType(dict(self.setup)))

    @property
    def prompt(self) -> str:
        """兼容 Live driver 对首个用户请求的读取。"""

        return self.prompts[0]

    def to_record(self) -> dict[str, object]:
        """仅输出场景结构，不用于 Live 脱敏报告。"""

        return {
            "id": self.id,
            "promptCount": len(self.prompts),
            "setup": dict(self.setup),
            "expectedDecision": self.expected_decision,
            "requiredItems": list(self.required_items),
            "forbiddenItems": list(self.forbidden_items),
            "maxOpenInteractions": self.max_open_interactions,
            "assertion": self.assertion,
        }


_READ_FORBIDDEN = ("QUESTION_CARD", "WORKFLOW_RUN")
_QUESTION_FORBIDDEN = ("WORKFLOW_RUN",)
_REJECT_FORBIDDEN = ()
# ExternalActionCommand 是持久化表事实，不是 Item 类型；Items API 通过
# EXTERNAL_ACTION_STATUS 的 commandId 关联它，不能把表名当作公开 Item kind。
_WORKFLOW_ITEMS = ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS")


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
        # 评测会验证中间 MANUAL_RETRY_REQUIRED 后自动重试；最终说明必须与成功事实一致。
        "FINISH_WORKFLOW_APPROVED",
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


LONG_SCENARIOS: tuple[LongEvalScenario, ...] = (
    LongEvalScenario(
        "long-early-request-reference",
        (
            "查询订单 ORDER-PAID-001 的状态",
            "继续查看刚才那个订单的物流详情",
        ),
        {"orderId": "ORDER-PAID-001"},
        "READ_TOOL",
        ("ORDER_DETAIL", "LOGISTICS_TIMELINE"),
        ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS"),
        0,
        "EARLY_REQUEST_REFERENCE",
    ),
    LongEvalScenario(
        "long-order-switch",
        (
            "查询订单 ORDER-PAID-001 的状态",
            "切换到订单 ORDER-SHIPPED-001，只回答后一个订单的最新状态",
        ),
        {"orderId": "ORDER-SHIPPED-001", "previousOrderId": "ORDER-PAID-001"},
        "READ_TOOL",
        ("ORDER_DETAIL",),
        ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS"),
        0,
        "ORDER_SWITCH",
    ),
    LongEvalScenario(
        "long-authorization-facts-changed",
        (
            "请给订单 ORDER-PAID-001 退款，原因是商品不需要了",
            "如果订单事实已经变化，请重新核验后再处理，不要沿用之前的授权",
            "现在告诉我退款事项的最新状态",
        ),
        {
            "action": "refund",
            "orderId": "ORDER-PAID-001",
            "reason": "商品不需要了",
            "answer": "APPROVE",
            "mutateBeforeDecision": True,
        },
        "READ_TOOL",
        ("WORKFLOW_RESULT",),
        (),
        0,
        "AUTHORIZATION_FACTS_CHANGED",
    ),
    LongEvalScenario(
        "long-execution-state-change",
        (
            "催发货订单 ORDER-PAID-001",
            "不用重新发起操作，告诉我刚才催发货事项现在处于什么状态",
        ),
        {
            "action": "expedite",
            "orderId": "ORDER-PAID-001",
            "externalStatus": "TEMPORARY_FAILURE",
        },
        "READ_TOOL",
        ("WORKFLOW_RESULT", "EXTERNAL_ACTION_STATUS"),
        (),
        0,
        "EXECUTION_STATE_CHANGE",
    ),
    LongEvalScenario(
        "long-large-tool-result-compaction",
        (
            "查询今天的订单，并保留当前筛选条件。" + "筛选条件记录 " * 900,
            "继续查询今天物流停滞三天的订单，并保留前面筛选条件。" + "物流条件记录 " * 900,
            "继续查看订单 ORDER-PAID-001 的物流详情",
        ),
        {"orderId": "ORDER-PAID-001", "requiresCompaction": True},
        "READ_TOOL",
        ("EXECUTION_EVENT", "LOGISTICS_TIMELINE"),
        ("ERROR",),
        0,
        "LARGE_TOOL_RESULT_COMPACTION",
    ),
    LongEvalScenario(
        "long-summary-invalidated-recovery",
        (
            "查询订单 ORDER-PAID-001 的状态",
            "继续查询订单 ORDER-SHIPPED-001 的状态",
            "回到最开始提到的订单，告诉我它当前的物流状态",
        ),
        {
            "orderId": "ORDER-PAID-001",
            "rebuildFromItems": True,
        },
        "READ_TOOL",
        ("ORDER_DETAIL", "LOGISTICS_TIMELINE"),
        ("ERROR",),
        0,
        "SUMMARY_INVALIDATED_RECOVERY",
    ),
)


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


def validate_long_scenarios(scenarios: tuple[LongEvalScenario, ...] = LONG_SCENARIOS) -> None:
    """检查长对话场景覆盖六类上下文风险且 ID 唯一。"""

    if len(scenarios) != 6:
        raise ValueError(f"长对话场景数量必须为 6，实际为 {len(scenarios)}")
    ids = [scenario.id for scenario in scenarios]
    if len(ids) != len(set(ids)):
        raise ValueError("长对话场景 id 必须唯一")
    assertions = {scenario.assertion for scenario in scenarios}
    expected = {
        "EARLY_REQUEST_REFERENCE",
        "ORDER_SWITCH",
        "AUTHORIZATION_FACTS_CHANGED",
        "EXECUTION_STATE_CHANGE",
        "LARGE_TOOL_RESULT_COMPACTION",
        "SUMMARY_INVALIDATED_RECOVERY",
    }
    if assertions != expected:
        raise ValueError(f"长对话断言覆盖不完整：{assertions}")
