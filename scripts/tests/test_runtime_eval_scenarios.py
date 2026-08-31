import unittest

from scripts.runtime_eval.scenarios import SCENARIOS, validate_scenarios


class RuntimeEvalScenarioTest(unittest.TestCase):
    """验证内部场景格式和固定场景覆盖范围。"""

    def test_fixed_scenarios_have_required_schema(self) -> None:
        validate_scenarios()
        expected_ids = {
            "exact-order",
            "today-orders",
            "stalled-logistics",
            "logistics-detail",
            "refund-missing-order",
            "refund-missing-reason",
            "refund-reject",
            "refund-approve",
            "expedite-failure",
            "expedite-manual-retry",
            "delete-reject",
            "delete-approve",
        }
        self.assertEqual(expected_ids, {scenario.id for scenario in SCENARIOS})
        for scenario in SCENARIOS:
            record = scenario.to_record()
            self.assertEqual(
                {
                    "id",
                    "prompt",
                    "setup",
                    "expectedDecision",
                    "requiredItems",
                    "forbiddenItems",
                    "maxOpenInteractions",
                    "expectedMutationCount",
                },
                set(record),
            )
