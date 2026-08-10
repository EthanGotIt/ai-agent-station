import unittest

from scripts.evaluation.runner import SCENARIOS, Scenario, matches, render


class EvaluationRunnerTest(unittest.TestCase):

    def test_matches_completed_structured_workflow(self) -> None:
        scenario = SCENARIOS[0]
        success, detail = matches(scenario, {
            "status": "COMPLETED",
            "workflowId": "order-inquiry",
            "operation": "QUERY",
            "result": {"cardType": "order_overview"},
        })

        self.assertTrue(success)
        self.assertEqual("ok", detail)

    def test_report_marks_failed_scenario(self) -> None:
        report = render([(Scenario("x", "x", "COMPLETED", "workflow", "QUERY", None), False, "status=FAILED")])

        self.assertIn("0/1", report)
        self.assertIn("FAIL", report)


if __name__ == "__main__":
    unittest.main()
