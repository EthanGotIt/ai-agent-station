"""真实百炼验收脚本的纯本地安全测试。"""

from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Timer
import unittest
from unittest.mock import patch

from scripts.live_acceptance.runner import (
    DROP_CONFIRMATION,
    RESET_SCHEMAS,
    AcceptanceFailure,
    JdbcConnectionModel,
    SseConversation,
    SseEventModel,
    _confirm_preference_interventions,
    _require_only_tool_names,
    _assert_tool_subsequence,
    parse_dotenv,
    parse_jdbc_connection,
    redact_text,
    require_local_reset,
    safe_environment_summary,
    _render_report_markdown,
    _required_environment,
)


class LiveAcceptanceTest(unittest.TestCase):
    """验证脚本不会放宽本机删除边界或泄露机密。"""

    def test_parse_dotenv_rejects_shell_syntax(self) -> None:
        with TemporaryDirectory() as directory:
            dotenv = Path(directory) / ".env"
            dotenv.write_text("export DASHSCOPE_API_KEY=key\n", encoding="utf-8")

            with self.assertRaises(AcceptanceFailure):
                parse_dotenv(dotenv)

    def test_parse_jdbc_connection_extracts_non_sensitive_parts(self) -> None:
        connection = parse_jdbc_connection(
            "jdbc:mysql://127.0.0.1:3306/AI_AGENT_STATION?useSSL=false"
        )

        self.assertEqual("127.0.0.1", connection.host)
        self.assertEqual(3306, connection.port)
        self.assertEqual("AI_AGENT_STATION", connection.schema)

    def test_reset_requires_local_host_and_exact_confirmation(self) -> None:
        self.assertIn("AI_AGENT_STATION", RESET_SCHEMAS)
        with self.assertRaises(AcceptanceFailure):
            require_local_reset(
                JdbcConnectionModel("mysql.example.com", 3306, "AI_AGENT_STATION"),
                DROP_CONFIRMATION,
            )
        with self.assertRaises(AcceptanceFailure):
            require_local_reset(
                JdbcConnectionModel("127.0.0.1", 3306, "AI_AGENT_STATION"),
                "wrong",
            )
        require_local_reset(
            JdbcConnectionModel("127.0.0.1", 3306, "AI_AGENT_STATION"),
            DROP_CONFIRMATION,
        )

    def test_summary_and_redaction_never_return_secret(self) -> None:
        secret = "sk-0123456789abcdefghijklmnop"
        summary = safe_environment_summary({
            "MYSQL_URL": "jdbc:mysql://localhost/AI_AGENT_STATION",
            "AI_AGENT_ROUTER_MODEL": "qwen3.7-plus",
            "AI_AGENT_REACT_MODEL": "qwen3.7-plus",
            "AI_AGENT_ROUTER_THINKING_ENABLED": "true",
            "AI_AGENT_ROUTER_THINKING_BUDGET": "512",
            "AI_AGENT_REACT_THINKING_ENABLED": "true",
            "DASHSCOPE_API_KEY": secret,
            "MYSQL_PASSWORD": "local-password",
        })

        self.assertNotIn(secret, str(summary))
        self.assertEqual(
            {"enabled": True, "budget": "512"},
            summary["routerThinking"],
        )
        self.assertEqual("MCP_ONLY", summary["externalToolPolicy"])
        self.assertEqual("模型返回 [REDACTED_API_KEY]", redact_text(f"模型返回 {secret}"))
        self.assertEqual("[REDACTED]", redact_text("local-password", ["local-password"]))

    def test_sse_dispatch_ignores_empty_data(self) -> None:
        conversation = SseConversation("127.0.0.1", 8090, "user", {}, 1)

        conversation._dispatch("progress", [])
        conversation._dispatch("progress", ["queued"])

        self.assertEqual(1, len(conversation.events))
        self.assertEqual("queued", conversation.events[0].data)

    def test_sse_wait_reports_unexpected_terminal_status_without_waiting(self) -> None:
        conversation = SseConversation("127.0.0.1", 8090, "user", {}, 1)
        conversation._dispatch("done", ["FAILED"])

        with self.assertRaisesRegex(AcceptanceFailure, "终态 FAILED"):
            conversation.wait_for("done", "COMPLETED", 1)

    def test_sse_wait_reports_terminal_before_expected_event(self) -> None:
        conversation = SseConversation("127.0.0.1", 8090, "user", {}, 1)
        conversation._dispatch("done", ["FAILED"])

        with self.assertRaisesRegex(AcceptanceFailure, "收到 intervention 前"):
            conversation.wait_for("intervention", timeout_seconds=1)

    def test_sse_wait_until_finished_accepts_terminal_event_without_transport_eof(self) -> None:
        conversation = SseConversation("127.0.0.1", 8090, "user", {}, 1)
        conversation._dispatch("done", ["CANCELLED"])

        conversation.wait_until_finished(1)

    def test_real_acceptance_requires_bounded_router_thinking(self) -> None:
        environment = {
            "MYSQL_URL": "jdbc:mysql://localhost/AI_AGENT_STATION",
            "MYSQL_USERNAME": "root",
            "MYSQL_PASSWORD": "local-password",
            "DASHSCOPE_API_KEY": "test-key",
            "AI_AGENT_LIVE_TEST_ENABLED": "true",
            "AI_AGENT_ROUTER_MODEL": "qwen3.7-plus",
            "AI_AGENT_REACT_MODEL": "qwen3.7-plus",
            "AI_AGENT_ROUTER_THINKING_ENABLED": "true",
            "AI_AGENT_ROUTER_THINKING_BUDGET": "512",
            "AI_AGENT_REACT_THINKING_ENABLED": "true",
        }

        _required_environment(environment)

        environment["AI_AGENT_ROUTER_THINKING_ENABLED"] = "false"
        with self.assertRaises(AcceptanceFailure):
            _required_environment(environment)


    def test_markdown_report_excludes_case_detail(self) -> None:
        report = {
            "status": "PASSED",
            "startedAt": "2026-08-10T00:00:00+00:00",
            "finishedAt": "2026-08-10T00:01:00+00:00",
            "databaseReset": "COMPLETED",
            "environment": {"models": {"router": "qwen3.7-plus", "react": "qwen3.7-plus"}},
            "cases": [{"name": "react_case", "status": "PASSED", "duration_ms": 123,
                       "detail": {"prompt": "must never appear"}}],
        }

        markdown = _render_report_markdown(report)

        self.assertIn("`react_case`", markdown)
        self.assertNotIn("must never appear", markdown)

    def test_skill_stability_report_shows_only_aggregate_success_rate(self) -> None:
        report = {
            "status": "PASSED",
            "environment": {"models": {}},
            "cases": [],
            "skillStability": {
                "runs": 5,
                "scenarios": {
                    "single_order_review": {"passedRuns": 5, "requiredRuns": 5},
                },
            },
        }

        markdown = _render_report_markdown(report)

        self.assertIn("## Skill 稳定性", markdown)
        self.assertIn("5/5", markdown)

    def test_tool_subsequence_accepts_lifecycle_status_suffixes(self) -> None:
        events = (
            SseEventModel("tool", "load_skill_through_path", 0.0),
            SseEventModel("tool", "load_skill_through_path:SUCCESS", 0.1),
            SseEventModel("tool", "get_order_snapshot", 0.2),
            SseEventModel("tool", "get_logistics_trace:SUCCESS", 0.3),
        )

        _assert_tool_subsequence(
            events,
            ("load_skill_through_path", "get_order_snapshot", "get_logistics_trace"),
        )

        with self.assertRaises(AcceptanceFailure):
            _assert_tool_subsequence(events, ("get_after_sales_policy",))

    def test_preference_intervention_accepts_multiple_preference_writes_only(self) -> None:
        _require_only_tool_names(
            ["save_session_preference", "save_session_preference"],
            "save_session_preference",
            "Skill 偏好场景",
        )

        with self.assertRaisesRegex(AcceptanceFailure, "Skill 偏好场景"):
            _require_only_tool_names(
                ["save_session_preference", "get_order_snapshot"],
                "save_session_preference",
                "Skill 偏好场景",
            )

    def test_preference_interventions_confirm_each_reply_until_completed(self) -> None:
        conversation = SseConversation("127.0.0.1", 8090, "user", {}, 1)
        conversation._dispatch("intervention", [
            '{"replyId":"reply-1","tools":[{"toolCallId":"tool-1",'
            '"toolName":"save_session_preference"}]}'
        ])
        timer = Timer(0.01, lambda: conversation._dispatch("done", ["COMPLETED"]))
        timer.start()
        try:
            with patch("scripts.live_acceptance.runner._http_json", return_value=(200, {"accepted": True})) as http:
                _confirm_preference_interventions(
                    "127.0.0.1", 8090, "user", {"requestId": "request-1", "sessionId": "session-1"},
                    conversation, 1,
                )
            self.assertEqual(1, http.call_count)
        finally:
            timer.cancel()


if __name__ == "__main__":
    unittest.main()
