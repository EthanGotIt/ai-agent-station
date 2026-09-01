import json
import tempfile
import unittest
from pathlib import Path

from scripts.runtime_eval.live_runner import sanitize_observation, write_summary


class LiveRunnerTest(unittest.TestCase):
    """验证本机评测只保存结构化摘要。"""

    def test_sanitizes_to_safe_fields(self) -> None:
        sanitized = sanitize_observation(
            {
                "scenarioId": "exact-order",
                "actualDecision": "READ_TOOL",
                "itemKinds": ["ORDER_DETAIL"],
            }
        )
        self.assertEqual(
            {"scenarioId": "exact-order", "actualDecision": "READ_TOOL", "itemKinds": ["ORDER_DETAIL"]},
            sanitized,
        )

    def test_rejects_raw_model_fields(self) -> None:
        with self.assertRaises(ValueError):
            sanitize_observation({"scenarioId": "exact-order", "thinking": "private"})

    def test_summary_contains_no_raw_fields(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report_dir = Path(directory)
            write_summary(
                [{"scenarioId": "exact-order", "actualDecision": "READ_TOOL"}],
                report_dir,
            )
            payload = json.loads((report_dir / "live-summary.json").read_text(encoding="utf-8"))
            self.assertEqual([{"scenarioId": "exact-order", "actualDecision": "READ_TOOL"}], payload["cases"])
            self.assertNotIn("thinking", (report_dir / "live-summary.md").read_text(encoding="utf-8"))
