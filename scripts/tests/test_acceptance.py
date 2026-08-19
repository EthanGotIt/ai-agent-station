import unittest
from unittest.mock import patch

from scripts.acceptance.runner import run


class AcceptanceRunnerTest(unittest.TestCase):
    """验证验收脚本的核心幂等断言。"""

    def test_checks_duplicate_request(self) -> None:
        responses = iter([
            (200, {"items": []}),
            (200, {"threadId": "thread-1"}),
            (200, {"items": []}),
            (202, {"turnId": "turn-1"}),
            (202, {"turnId": "turn-1"}),
        ])
        with patch("scripts.acceptance.runner._request", side_effect=lambda *args: next(responses)):
            result = run("http://localhost:8090", "demo-user-1")
        self.assertEqual(
            ("thread-list", "thread-create", "item-recovery", "turn-accepted", "turn-idempotency"),
            result.checks,
        )
