import unittest

from scripts.runtime_eval.scenarios import LONG_SCENARIOS, SCENARIOS, validate_long_scenarios, validate_scenarios


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

    def test_long_scenarios_cover_context_risks(self) -> None:
        validate_long_scenarios()
        self.assertEqual(6, len(LONG_SCENARIOS))
        self.assertEqual(
            {
                "EARLY_REQUEST_REFERENCE",
                "ORDER_SWITCH",
                "AUTHORIZATION_FACTS_CHANGED",
                "EXECUTION_STATE_CHANGE",
                "LARGE_TOOL_RESULT_COMPACTION",
                "SUMMARY_INVALIDATED_RECOVERY",
            },
            {scenario.assertion for scenario in LONG_SCENARIOS},
        )
        for scenario in LONG_SCENARIOS:
            self.assertGreaterEqual(len(scenario.prompts), 2)
            self.assertEqual(len(scenario.prompts), scenario.to_record()["promptCount"])
