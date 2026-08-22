package cn.ethan.core.agent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * QuestionCard 契约测试：验证回答 Turn 的预留、入队、释放和同 Turn 关闭状态。
 *
 * @author ethan
 * @date 2026-08-20
 */
class AgentWorkflowQuestionModelTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void reservesAndEnqueuesOneAnswerTurnWithVersionProgression() {
        AgentWorkflowQuestionModel question = question();

        AgentWorkflowQuestionModel reserved = question.reserveAnswerTurn("answer-turn-1");
        AgentWorkflowQuestionModel enqueued = reserved.answerTurnEnqueued();

        assertEquals(1, reserved.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED,
                reserved.answerEnqueueStatus());
        assertEquals(2, enqueued.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                enqueued.answerEnqueueStatus());
        assertEquals("answer-turn-1", enqueued.answerTurnId());
    }

    @Test
    void releaseClearsReservationAndAdvancesVersion() {
        AgentWorkflowQuestionModel reserved = question().reserveAnswerTurn("answer-turn-1");

        AgentWorkflowQuestionModel released = reserved.releaseAnswerTurn();

        assertEquals(2, released.version());
        assertNull(released.answerTurnId());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                released.answerEnqueueStatus());
        assertEquals(AgentWorkflowQuestionStatusEnum.OPEN, released.status());
    }

    @Test
    void answeredStateRejectsReservedAnswerTurn() {
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1", 2,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.ANSWERED, NOW, NOW,
                "answer-turn-1", AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED
        ));
    }

    @Test
    void rejectsMissingSqlRequiredFieldsAndNegativeVersion() {
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "", "user-1", "question-1", "checkpoint-1", 0,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1", -1,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null
        ));
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1", 0,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null,
                "answer-turn-1", AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE
        ));
    }

    @Test
    void validatesAnswersAgainstQuestionCardSchema() {
        AgentWorkflowQuestionModel question = schemaQuestion();

        assertEquals(Map.of("decision", "APPROVE", "note", "确认"),
                question.validateAnswers(Map.of("decision", " APPROVE ", "note", "确认")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("decision", "APPROVE", "apiKey", "secret")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("note", "缺少决定")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("decision", "CONFIRM")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("decision", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> question.validateAnswers(Map.of("decision", "APPROVE", "note", "x".repeat(17))));
    }

    @Test
    void allowsCustomValueOnlyWhenQuestionFieldExplicitlyPermitsIt() {
        AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1", 0,
                "选择原因", "请选择或填写其他原因", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null,
                null, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                List.of(new AgentWorkflowQuestionFieldModel(
                        "reason", true, 32, List.of("商品不符", "物流停滞"), true)));

        assertEquals(Map.of("reason", "包装破损"), question.validateAnswers(Map.of("reason", "包装破损")));
    }

    private AgentWorkflowQuestionModel question() {
        return new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1", 0,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null
        );
    }

    private AgentWorkflowQuestionModel schemaQuestion() {
        return new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "turn-1", "user-1", "question-1", "checkpoint-1", 0,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null,
                null, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                List.of(
                        new AgentWorkflowQuestionFieldModel(
                                "decision", true, 16, List.of("APPROVE", "REJECT")),
                        new AgentWorkflowQuestionFieldModel("note", false, 16, List.of()))
        );
    }
}
