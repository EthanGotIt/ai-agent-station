"""退款可靠性验收脚本的纯本地测试。"""

import http.client
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from scripts.live_acceptance.runner import AcceptanceFailure, JdbcConnectionModel
from scripts.refund_acceptance.runner import (
    DROP_CONFIRMATION,
    ApplicationProcessManager,
    RefundChannelAction,
    RefundChannelSimulator,
    _render_markdown,
    _write_report,
    assert_report_safe,
    require_refund_reset,
    required_environment,
)


class RefundAcceptanceTest(unittest.TestCase):
    """验证模拟渠道、重启编排、数据库守卫与报告脱敏。"""

    def test_simulator_applies_response_sequence_and_caches_completed_key(self) -> None:
        simulator = RefundChannelSimulator()
        simulator.configure("ORDER-TEST-001", [
            RefundChannelAction("FAILED", "TEMPORARY_FAILURE"),
            RefundChannelAction("COMPLETED"),
        ])
        simulator.start()
        try:
            first = self._refund(simulator, "refund-1", "ORDER-TEST-001")
            second = self._refund(simulator, "refund-1", "ORDER-TEST-001")
            third = self._refund(simulator, "refund-1", "ORDER-TEST-001")

            self.assertEqual("FAILED", first["status"])
            self.assertEqual("TEMPORARY_FAILURE", first["failureCode"])
            self.assertEqual("COMPLETED", second["status"])
            self.assertEqual("COMPLETED", third["status"])
            self.assertEqual(3, simulator.call_count("ORDER-TEST-001"))
            self.assertEqual(1, simulator.unique_key_count("ORDER-TEST-001"))
            self.assertEqual(1, simulator.effect_count("ORDER-TEST-001"))
        finally:
            simulator.stop()

    def test_simulator_rejects_extra_context_and_counts_non_refund_calls(self) -> None:
        simulator = RefundChannelSimulator()
        simulator.configure("ORDER-TEST-001", [RefundChannelAction("COMPLETED")])
        simulator.start()
        try:
            code, _ = self._post(simulator, "/refunds", {
                "refundId": "refund-1",
                "orderId": "ORDER-TEST-001",
                "amount": 99,
                "currency": "CNY",
                "userId": "must-not-leave-application",
            })
            model_code, _ = self._post(simulator, "/chat/completions", {})

            self.assertEqual(400, code)
            self.assertEqual(503, model_code)
            self.assertEqual(1, simulator.unexpected_calls)
            self.assertEqual({"UNEXPECTED_FIELDS": 1}, simulator.rejection_counts)
            self.assertEqual(0, simulator.effect_count("ORDER-TEST-001"))
        finally:
            simulator.stop()

    def test_simulator_accepts_chunked_jdk_style_request_body(self) -> None:
        simulator = RefundChannelSimulator()
        simulator.configure("ORDER-TEST-001", [RefundChannelAction("COMPLETED")])
        simulator.start()
        try:
            connection = http.client.HTTPConnection(
                "127.0.0.1", int(simulator.base_url.rsplit(":", 1)[1]), timeout=2
            )
            payload = json.dumps({
                "refundId": "refund-1",
                "orderId": "ORDER-TEST-001",
                "amount": 99,
                "currency": "CNY",
            }).encode("utf-8")
            try:
                connection.request("POST", "/refunds", body=iter([payload]), headers={
                    "Content-Type": "application/json",
                    "Idempotency-Key": "refund-1",
                }, encode_chunked=True)
                response = connection.getresponse()
                response.read()
            finally:
                connection.close()

            self.assertEqual(200, response.status)
            self.assertEqual({}, simulator.rejection_counts)
            self.assertEqual(1, simulator.effect_count("ORDER-TEST-001"))
        finally:
            simulator.stop()

    def test_reset_requires_local_expected_schema_and_exact_confirmation(self) -> None:
        with self.assertRaises(AcceptanceFailure):
            require_refund_reset(
                JdbcConnectionModel("mysql.example.com", 3306, "AI_AGENT_STATION"),
                DROP_CONFIRMATION,
            )
        with self.assertRaises(AcceptanceFailure):
            require_refund_reset(
                JdbcConnectionModel("127.0.0.1", 3306, "OTHER_SCHEMA"),
                DROP_CONFIRMATION,
            )
        with self.assertRaises(AcceptanceFailure):
            require_refund_reset(
                JdbcConnectionModel("127.0.0.1", 3306, "AI_AGENT_STATION"),
                "wrong",
            )

        require_refund_reset(
            JdbcConnectionModel("127.0.0.1", 3306, "AI_AGENT_STATION"),
            DROP_CONFIRMATION,
        )

    def test_environment_does_not_require_model_credentials(self) -> None:
        connection = required_environment({
            "MYSQL_URL": "jdbc:mysql://localhost:3306/AI_AGENT_STATION?useSSL=false",
            "MYSQL_USERNAME": "root",
            "MYSQL_PASSWORD": "",
            "SERVER_PORT": "18090",
        })

        self.assertEqual("localhost", connection.host)
        self.assertEqual("AI_AGENT_STATION", connection.schema)

    def test_application_restart_stops_before_starting_again(self) -> None:
        manager = ApplicationProcessManager(
            Path.cwd(), {"SERVER_PORT": "18090"}, "http://127.0.0.1:18081", Path("app.log")
        )
        calls: list[str] = []
        with patch.object(manager, "stop", side_effect=lambda: calls.append("stop")), patch.object(
                manager, "start", side_effect=lambda: calls.append("start")
        ):
            manager.restart()

        self.assertEqual(["stop", "start"], calls)

    def test_report_rejects_secrets_and_markdown_exposes_only_aggregates(self) -> None:
        secret = "local-database-password"
        report = {
            "status": "PASSED",
            "cases": [{
                "name": "automatic_refund_success",
                "status": "PASSED",
                "duration_ms": 12,
                "detail": {
                    "attemptCount": 1,
                    "channelCalls": 1,
                    "uniqueEffects": 1,
                    "requestBody": "must-not-appear",
                },
            }],
        }

        markdown = _render_markdown(report)
        self.assertIn("`automatic_refund_success`", markdown)
        self.assertNotIn("must-not-appear", markdown)
        assert_report_safe(report, [secret])

        report["failure"] = secret
        with self.assertRaises(AcceptanceFailure):
            assert_report_safe(report, [secret])
        with TemporaryDirectory() as directory:
            with self.assertRaises(AcceptanceFailure):
                _write_report(Path(directory), report, [secret])

    def _refund(
            self,
            simulator: RefundChannelSimulator,
            refund_id: str,
            order_id: str,
    ) -> dict[str, object]:
        code, body = self._post(simulator, "/refunds", {
            "refundId": refund_id,
            "orderId": order_id,
            "amount": 99,
            "currency": "CNY",
        }, refund_id)
        self.assertEqual(200, code)
        return body

    def _post(
            self,
            simulator: RefundChannelSimulator,
            path: str,
            payload: dict[str, object],
            idempotency_key: str = "refund-1",
    ) -> tuple[int, dict[str, object]]:
        connection = http.client.HTTPConnection(
            "127.0.0.1", int(simulator.base_url.rsplit(":", 1)[1]), timeout=2
        )
        body = json.dumps(payload).encode("utf-8")
        try:
            connection.request("POST", path, body=body, headers={
                "Content-Type": "application/json",
                "Idempotency-Key": idempotency_key,
            })
            response = connection.getresponse()
            response_body = json.loads(response.read().decode("utf-8"))
            return response.status, response_body
        finally:
            connection.close()


if __name__ == "__main__":
    unittest.main()
