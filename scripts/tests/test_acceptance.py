import unittest
from unittest.mock import patch

from scripts.acceptance.runner import (
    _item_page_fingerprint,
    _run_order_service_scenarios,
    run,
)


class _FakeOrderFixture:
    """为 runner 单元测试提供不产生外部副作用的订单夹具替身。"""

    def __init__(self) -> None:
        self.stats = {
            "idempotencyRecords": 0,
            "businessMutations": 0,
            "injectedFailures": 0,
        }
        self.responses: dict[str, dict] = {}
        self.expedite_attempts = 0
        self.deleted = False

    def request(
        self,
        _base_url: str,
        method: str,
        path: str,
        _user_id: str,
        _body: object | None = None,
        extra_headers: dict[str, str] | None = None,
    ) -> tuple[int, dict]:
        if method == "GET" and path == "/_fixture/stats":
            return 200, dict(self.stats)
        if method == "GET" and path == "/orders/ORDER-EXT-STALLED-001/logistics":
            return 200, [{"eventId": "event-1"}, {"eventId": "event-2"}]
        if method == "GET" and path == "/orders/ORDER-EXT-REFUND-001":
            return 200, {"status": "REFUNDED"}
        if method == "GET" and path == "/orders/ORDER-EXT-TODAY-001":
            return 200, {"logisticsStatus": "EXPEDITE_REQUESTED"}
        if method == "GET" and path == "/orders/ORDER-EXT-DELIVERED-001":
            return (404, {}) if self.deleted else (200, {"status": "DELIVERED"})
        if method == "GET" and path == "/orders/ORDER-EXT-DELIVERED-001/logistics":
            return (404, []) if self.deleted else (200, [{"eventId": "event-3"}])

        key = (extra_headers or {}).get("Idempotency-Key")
        if not isinstance(key, str):
            raise AssertionError("测试请求缺少幂等键")
        if method == "POST" and path == "/orders/ORDER-EXT-REFUND-001/refund":
            if key not in self.responses:
                self.stats["idempotencyRecords"] += 1
                self.stats["businessMutations"] += 1
                self.responses[key] = {
                    "success": True,
                    "retryable": False,
                    "code": "REFUNDED",
                }
            return 200, dict(self.responses[key])
        if method == "POST" and path == "/orders/ORDER-EXT-TODAY-001/expedite":
            self.expedite_attempts += 1
            if self.expedite_attempts == 1:
                self.stats["injectedFailures"] += 1
                return 200, {
                    "success": False,
                    "retryable": True,
                    "code": "FIXTURE_TRANSIENT_FAILURE",
                }
            if key not in self.responses:
                self.stats["idempotencyRecords"] += 1
                self.stats["businessMutations"] += 1
                self.responses[key] = {
                    "success": True,
                    "retryable": False,
                    "code": "EXPEDITED",
                }
            return 200, dict(self.responses[key])
        if method == "DELETE" and path == "/orders/ORDER-EXT-DELIVERED-001":
            if key not in self.responses:
                self.stats["idempotencyRecords"] += 1
                self.stats["businessMutations"] += 1
                self.deleted = True
                self.responses[key] = {
                    "success": True,
                    "retryable": False,
                    "code": "ORDER_DELETED",
                }
            return 200, dict(self.responses[key])
        raise AssertionError(f"未处理的夹具请求：{method} {path}")


class AcceptanceRunnerTest(unittest.TestCase):
    """验证验收脚本的恢复、幂等和开放交互断言。"""

    def test_checks_duplicate_request_and_refresh_recovery(self) -> None:
        page = {"items": [], "afterSequence": 0, "nextAfterSequence": 0, "hasMore": False}
        responses = iter(
            [
                (200, {"items": []}),
                (200, {"threadId": "thread-1"}),
                (200, page),
                (204, {}),
                (204, {}),
                (200, page),
                (202, {"turnId": "turn-1"}),
                (202, {"turnId": "turn-1"}),
                (200, {"turnId": "turn-1", "items": []}),
            ]
        )
        with patch(
            "scripts.acceptance.runner._request",
            side_effect=lambda *args, **kwargs: next(responses),
        ):
            result = run("http://localhost:8090", "demo-user-1")
        self.assertEqual(
            (
                "thread-list",
                "thread-create",
                "item-recovery",
                "interaction-uniqueness",
                "refresh-recovery",
                "turn-accepted",
                "turn-idempotency",
                "execution-replay",
            ),
            result.checks,
        )

    def test_rejects_non_advancing_item_cursor(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "nextAfterSequence"):
            _item_page_fingerprint(
                {
                    "items": [{"itemId": "item-1", "sequence": 1}],
                    "afterSequence": 0,
                    "nextAfterSequence": 0,
                },
                "测试页",
            )

    def test_rejects_open_interaction_replacement(self) -> None:
        page = {"items": [], "afterSequence": 0, "nextAfterSequence": 0, "hasMore": False}
        responses = iter(
            [
                (200, {"items": []}),
                (200, {"threadId": "thread-1"}),
                (200, page),
                (200, {"interactionId": "question-1", "type": "QUESTION_CARD"}),
                (200, {"interactionId": "question-2", "type": "QUESTION_CARD"}),
            ]
        )
        with patch(
            "scripts.acceptance.runner._request",
            side_effect=lambda *args, **kwargs: next(responses),
        ):
            with self.assertRaisesRegex(RuntimeError, "不同的开放交互"):
                run("http://localhost:8090", "demo-user-1")

    def test_order_scenarios_gate_delete_and_require_retry(self) -> None:
        fixture = _FakeOrderFixture()
        with patch("scripts.acceptance.runner._request", side_effect=fixture.request):
            scenarios = _run_order_service_scenarios(
                "http://fixture",
                "demo-user-1",
                allow_destructive_delete=False,
                require_expedite_retry=True,
                expedite_max_attempts=3,
            )
        self.assertEqual(
            ("logistics", "refund-idempotency", "expedite-retry", "delete-gated"),
            scenarios,
        )
        self.assertFalse(fixture.deleted)

    def test_order_scenarios_can_delete_only_when_explicitly_enabled(self) -> None:
        fixture = _FakeOrderFixture()
        with patch("scripts.acceptance.runner._request", side_effect=fixture.request):
            scenarios = _run_order_service_scenarios(
                "http://fixture",
                "demo-user-1",
                allow_destructive_delete=True,
                require_expedite_retry=True,
                expedite_max_attempts=3,
            )
        self.assertEqual(
            ("logistics", "refund-idempotency", "expedite-retry", "delete-idempotency"),
            scenarios,
        )
        self.assertTrue(fixture.deleted)
