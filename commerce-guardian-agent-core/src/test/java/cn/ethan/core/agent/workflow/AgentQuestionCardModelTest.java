package cn.ethan.core.agent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * QuestionCard 新契约测试：确认提问模型不承载授权语义，并覆盖回答 Turn 生命周期。
 *
 * @author ethan
 * @date 2026-08-27
 */
class AgentQuestionCardModelTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void agentQuestionDoesNotRequireWorkflowRun() {
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
}
