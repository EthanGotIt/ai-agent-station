import tempfile
import unittest
from pathlib import Path

from scripts.runtime_eval.live_driver import (
    LiveDriverConfig,
    _run_case,
    _long_assertion_passed,
    choose_model_profile,
)
from scripts.runtime_eval.scenarios import LONG_SCENARIOS, SCENARIOS


class _ReadClient:
    """为 Live driver 单测提供只读 Agent HTTP 替身。"""

    order_service_url = None

    def create_thread(self, _user_id, _title):
        return "thread-read"

    def submit_turn(self, _thread_id, _user_id, _message, _request_id):
        return "turn-read"

    def read_items(self, _thread_id, _user_id):
        return [
            {"itemId": "item-0", "sequence": 1, "type": "TOOL_RESULT", "data": {}},
            {"itemId": "item-1", "sequence": 2, "type": "ORDER_DETAIL", "data": {}},
            {
                "itemId": "item-2",
                "sequence": 3,
                "type": "AGENT_DECISION",
                "data": {"decision": "FINISH", "code": "CONTROL_TOOL"},
            },
        ]

    def get_interaction(self, _thread_id, _user_id):
        return None

    def execution_status(self, _turn_id, _user_id):
        return "COMPLETED"

    def fixture_stats(self, _user_id):
        return None


class _WorkflowClient:
    """验证 QuestionCard、Checkpoint 和 Worker 状态被顺序推进。"""

    order_service_url = None

    def __init__(self):
        self.interactions = [
            {
                "type": "QUESTION_CARD",
                "interactionId": "question-1",
                "version": 0,
                "runId": "run-1",
                "fieldsJson": '{"step":"REASON","fields":[{"name":"reason","required":true}]}'
            },
            {
                "type": "WORKFLOW_CHECKPOINT",
                "interactionId": "checkpoint-1",
                "version": 0,
                "runId": "run-1",
                "factsFingerprint": "facts-v1",
            },
            None,
        ]
        self.stats_reads = 0

    def create_thread(self, _user_id, _title):
        return "thread-workflow"

    def submit_turn(self, _thread_id, _user_id, _message, _request_id):
        return "turn-workflow"

    def read_items(self, _thread_id, _user_id):
        if len(self.interactions) > 1:
            return [{"itemId": "item-open", "sequence": 1, "type": "WORKFLOW_CHECKPOINT", "data": {}}]
        return [
            {"itemId": "item-result", "sequence": 1, "type": "WORKFLOW_RESULT", "data": {"runId": "run-1", "status": "SUCCEEDED"}},
            {"itemId": "item-action", "sequence": 2, "type": "EXTERNAL_ACTION_STATUS", "data": {"runId": "run-1", "status": "SUCCEEDED", "commandId": "command-1"}},
            {"itemId": "item-command", "sequence": 3, "type": "EXTERNAL_ACTION_COMMAND", "data": {"runId": "run-1"}},
            {"itemId": "item-finish", "sequence": 4, "type": "AGENT_DECISION", "data": {"runId": "run-1", "decision": "FINISH"}},
        ]

    def get_interaction(self, _thread_id, _user_id):
        return self.interactions[0]

    def answer_question(self, _interaction, _user_id, _answers):
        self.interactions.pop(0)
        return True

    def decide_checkpoint(self, _interaction, _user_id, _decision):
        self.interactions.pop(0)
        return True

    def execution_status(self, _turn_id, _user_id):
        return "COMPLETED"

    def fixture_stats(self, _user_id):
        self.stats_reads += 1
        return {"idempotencyRecords": 0, "businessMutations": 0 if self.stats_reads == 1 else 1, "injectedFailures": 0}


class LiveDriverTest(unittest.TestCase):
    def test_read_case_produces_safe_fact_grounded_observation(self):
        with tempfile.TemporaryDirectory() as directory:
            config = LiveDriverConfig(report_dir=Path(directory), poll_interval_seconds=0, timeout_seconds=1)
            observation = _run_case(_ReadClient(), config, SCENARIOS[0], 1)
        self.assertEqual("READ_TOOL", observation["actualDecision"])
        self.assertTrue(observation["safetyPassed"])
        self.assertTrue(observation["factsGrounded"])
        self.assertNotIn("prompt", observation)

    def test_workflow_case_answers_question_and_checkpoint_once(self):
        with tempfile.TemporaryDirectory() as directory:
            config = LiveDriverConfig(report_dir=Path(directory), poll_interval_seconds=0, timeout_seconds=1)
            observation = _run_case(_WorkflowClient(), config, SCENARIOS[7], 1)
        self.assertEqual("FINISH_WORKFLOW_APPROVED", observation["actualDecision"])
        self.assertEqual(1, observation["mutationCount"])
        self.assertTrue(observation["idempotent"])
        self.assertTrue(observation["safetyPassed"])

    def test_model_selection_prefers_flash_only_when_faster_on_routing_tie(self):
        records = []
        ids = [scenario.id for scenario in SCENARIOS]
        for profile, duration in (("deepseek-v4-pro", 100), ("deepseek-v4-flash", 80)):
            for repetition in range(3):
                for scenario_id in ids:
                    records.append({
                        "modelProfile": profile,
                        "scenarioId": scenario_id,
                        "repetition": repetition + 1,
                        "safetyPassed": True,
                        "routingPassed": True,
                        "factsGrounded": True,
                        "durationMs": duration,
                    })
        self.assertEqual("deepseek-v4-flash", choose_model_profile(records))

    def test_long_assertions_require_persisted_facts(self):
        stale = next(item for item in LONG_SCENARIOS if item.assertion == "AUTHORIZATION_FACTS_CHANGED")
        items = [
            {"type": "WORKFLOW_RESULT", "data": {"code": "FACTS_CHANGED"}},
            {"type": "WORKFLOW_DECISION", "data": {}},
            {"type": "WORKFLOW_DECISION", "data": {}},
        ]
        self.assertTrue(_long_assertion_passed(stale, items, 1, {}))
        self.assertFalse(_long_assertion_passed(stale, items, 0, {}))

    def test_model_selection_requires_long_dialogue_when_present(self):
        records = []
        for scenario in SCENARIOS:
            records.extend(
                {
                    "modelProfile": "deepseek-v4-pro",
                    "scenarioId": scenario.id,
                    "safetyPassed": True,
                    "routingPassed": True,
                    "factsGrounded": True,
                    "durationMs": 100,
                }
                for _ in range(3)
            )
        for scenario in LONG_SCENARIOS:
            records.extend(
                {
                    "modelProfile": "deepseek-v4-pro",
                    "scenarioId": scenario.id,
                    "safetyPassed": True,
                    "routingPassed": True,
                    "factsGrounded": True,
                    "longAssertionPassed": scenario is not LONG_SCENARIOS[-1],
                    "durationMs": 100,
                }
                for _ in range(3)
            )
        self.assertIsNone(choose_model_profile(records))


if __name__ == "__main__":
    unittest.main()
