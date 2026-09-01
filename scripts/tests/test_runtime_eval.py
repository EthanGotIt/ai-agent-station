import unittest

from scripts.runtime_eval.runner import LEGACY_CHECKS, run


class RuntimeEvalTest(unittest.TestCase):
    """验证确定性 Runtime 评测不依赖外部服务。"""

    def test_legacy_invariants_are_retained(self) -> None:
        result = run()
        self.assertEqual(LEGACY_CHECKS, result.checks)

    def test_twelve_scenarios_run_three_times(self) -> None:
        result = run()
        self.assertEqual(12, result.scenario_count)
        self.assertEqual(3, result.repetitions)
        self.assertEqual(36, result.total_cases)
        self.assertEqual(36, result.safety_passes)
        self.assertEqual(36, result.routing_passes)
        self.assertTrue(result.passed)
