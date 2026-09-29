package cn.ethan.core.agent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workflow 契约测试：验证 QuestionCard、Checkpoint 和 WorkflowTask 的状态不变量。
 *
 * @author ethan
 * @date 2026-08-27
 */
class AgentQuestionCardModelTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void agentQuestionDoesNotRequireWorkflowTask() {
        AgentQuestionCardModel question = AgentQuestionCardModel.agent(
                "question-1", "thread-1", "turn-1", "user-1", "缺少订单号", "请补充订单号", "[]",
                List.of(new AgentQuestionFieldModel("orderId", true, 64, List.of())), NOW);

        assertNull(question.runId());
        assertEquals(AgentQuestionCardResumeTargetEnum.AGENT, question.resumeTarget());
        assertEquals(AgentQuestionCardStatusEnum.OPEN, question.status());
        assertEquals(Map.of("orderId", "ORDER-1"), question.validateAnswers(Map.of("orderId", " ORDER-1 ")));
    }

    @Test
    void workflowQuestionRequiresRunAndUsesWorkflowResumeTarget() {
        AgentQuestionCardModel question = AgentQuestionCardModel.workflow(
                "question-1", "run-1", "thread-1", "turn-1", "user-1", 2,
                "缺少原因", "请补充退款原因", "[]", List.of(), NOW);

        assertEquals("run-1", question.runId());
        assertEquals(AgentQuestionCardResumeTargetEnum.WORKFLOW, question.resumeTarget());
        assertThrows(IllegalArgumentException.class, () -> AgentQuestionCardModel.workflow(
                "question-2", null, "thread-1", "turn-1", "user-1", 2,
                "标题", "问题", "[]", List.of(), NOW));
    }

    @Test
    void answerReservationEnqueueAndCloseAreMonotonic() {
        AgentQuestionCardModel question = AgentQuestionCardModel.agent(
                "question-1", "thread-1", "turn-1", "user-1", "标题", "问题", "[]",
                List.of(new AgentQuestionFieldModel("answer", true, 32, List.of())), NOW);

        AgentQuestionCardModel reserved = question.reserveAnswerTurn("answer-turn-1");
        AgentQuestionCardModel enqueued = reserved.answerTurnEnqueued();
        AgentQuestionCardModel answered = enqueued.answer(NOW.plusSeconds(1));

        assertEquals(1, reserved.version());
        assertEquals(2, enqueued.version());
        assertEquals(3, answered.version());
        assertEquals(AgentQuestionCardStatusEnum.ANSWERED, answered.status());
        assertEquals(AgentQuestionCardAnswerEnqueueStatusEnum.CONSUMED, answered.answerEnqueueStatus());
        assertThrows(IllegalStateException.class, () -> answered.answer(NOW.plusSeconds(2)));
    }

    @Test
    void releasingReservationInvalidatesTheOldAnswerTurn() {
        AgentQuestionCardModel reserved = AgentQuestionCardModel.agent(
                "question-1", "thread-1", "turn-1", "user-1", "标题", "问题", "[]", List.of(), NOW)
                .reserveAnswerTurn("answer-turn-1");

        AgentQuestionCardModel released = reserved.releaseAnswerTurn();

        assertEquals(2, released.version());
        assertNull(released.answerTurnId());
        assertEquals(AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE, released.answerEnqueueStatus());
        assertThrows(IllegalStateException.class, () -> released.answerTurnEnqueued());
    }

    @Test
    void answerSchemaRejectsUnknownMissingAndInvalidValues() {
        AgentQuestionCardModel question = AgentQuestionCardModel.agent(
                "question-1", "thread-1", "turn-1", "user-1", "标题", "问题", "[]",
                List.of(new AgentQuestionFieldModel(
                        "decision", true, 16, List.of("APPROVE", "REJECT"))), NOW);

        assertEquals(Map.of("decision", "APPROVE"), question.validateAnswers(Map.of("decision", " APPROVE ")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("other", "value")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("decision", "CONFIRM")));
    }

    @Test
    void approvalAndRejectionAreTerminalDecisions() {
        AgentWorkflowCheckpointModel checkpoint = checkpoint();

        AgentWorkflowCheckpointModel approved = checkpoint.approve(NOW.plusSeconds(1));
        AgentWorkflowCheckpointModel rejected = checkpoint.reject(NOW.plusSeconds(1));

        assertEquals(1, approved.version());
        assertEquals(AgentWorkflowCheckpointStatusEnum.APPROVED, approved.status());
        assertEquals(AgentWorkflowDecisionEnum.APPROVE, approved.decision());
        assertEquals(AgentWorkflowCheckpointStatusEnum.REJECTED, rejected.status());
        assertEquals(AgentWorkflowDecisionEnum.REJECT, rejected.decision());
        assertThrows(IllegalStateException.class, () -> approved.reject(NOW.plusSeconds(2)));
    }

    @Test
    void supersededCheckpointHasNoDecisionAndCannotBeReused() {
        AgentWorkflowCheckpointModel superseded = checkpoint().supersede(NOW.plusSeconds(1));

        assertEquals(1, superseded.version());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED, superseded.status());
        assertNull(superseded.decision());
        assertThrows(IllegalStateException.class, () -> superseded.approve(NOW.plusSeconds(2)));
    }

    @Test
    void approvedCheckpointCanBeSupersededWhenFactsChangeBeforeExecution() {
        AgentWorkflowCheckpointModel approved = checkpoint().approve(NOW.plusSeconds(1));

        AgentWorkflowCheckpointModel superseded = approved.supersede(NOW.plusSeconds(2));

        assertEquals(2, superseded.version());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED, superseded.status());
        assertNull(superseded.decision());
    }

    @Test
    void checkpointRequiresStableFactsFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowCheckpointModel(
                "checkpoint-1", "run-1", "thread-1", "turn-1", "user-1", "AUTHORIZE",
                "REFUND", "ORDER-1", "退款", "", 0, AgentWorkflowCheckpointStatusEnum.OPEN,
                null, NOW, null));
    }

    @Test
    void manualRetryRequiredCanReturnToExternalActionAndComplete() {
        AgentWorkflowTaskModel manual = run(AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED, 1);

        AgentWorkflowTaskModel waiting = manual.status(
                AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, NOW.plusSeconds(1));
        AgentWorkflowTaskModel completed = waiting.status(
                AgentWorkflowStatusEnum.COMPLETED, NOW.plusSeconds(2));

        assertEquals(2L, waiting.version());
        assertEquals(AgentWorkflowStatusEnum.COMPLETED, completed.status());
        assertEquals(AgentWorkflowOrchestrationVersionEnum.LEGACY_V1,
                completed.orchestrationVersion());
    }

    @Test
    void immutableTerminalCannotBeRewritten() {
        AgentWorkflowTaskModel completed = run(AgentWorkflowStatusEnum.COMPLETED, 3);

        assertThrows(IllegalStateException.class,
                () -> completed.status(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, NOW));
    }

    @Test
    void sameStatusCannotAdvanceVersionWithoutAStateTransition() {
        AgentWorkflowTaskModel waiting = run(AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0);

        assertThrows(IllegalStateException.class,
                () -> waiting.status(AgentWorkflowStatusEnum.WAITING_USER_INPUT, NOW));
    }

    @Test
    void negativeVersionIsRejectedAtPersistenceBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> run(AgentWorkflowStatusEnum.WAITING_USER_INPUT, -1));
    }

    private AgentWorkflowCheckpointModel checkpoint() {
        return new AgentWorkflowCheckpointModel(
                "checkpoint-1", "run-1", "thread-1", "turn-1", "user-1", "AUTHORIZE",
                "REFUND", "ORDER-1", "退款订单 ORDER-1", "facts-v1", 0,
                AgentWorkflowCheckpointStatusEnum.OPEN, null, NOW, null);
    }

    private AgentWorkflowTaskModel run(AgentWorkflowStatusEnum status, long version) {
        return new AgentWorkflowTaskModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                status, version, NOW, NOW);
    }
}
