package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.execution.AgentWorkflowAnswerAdmissionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证 Core 创建和回答契约统一限制 clientRequestId 为 128 字符。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentTurnClientRequestIdTest {

    @Test
    void accepts128AndRejects129Characters() {
        String accepted = "a".repeat(128);
        String rejected = "a".repeat(129);

        assertDoesNotThrow(() -> turn(accepted));
        assertThrows(IllegalArgumentException.class, () -> turn(rejected));
        assertDoesNotThrow(() -> answerCommand(accepted));
        assertThrows(IllegalArgumentException.class, () -> answerCommand(rejected));
    }

    @Test
    void normalizesRequestIdBeforePersistenceAndAdmissionLookup() {
        assertEquals("request-1", turn("  request-1  ").clientRequestId());
        assertEquals("request-1", answerCommand("  request-1  ").clientRequestId());
    }

    private AgentTurnModel turn(String clientRequestId) {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", clientRequestId, "message",
                AgentTurnStatusEnum.QUEUED, 1, null, null, Instant.EPOCH, null, null);
    }

    private AgentWorkflowAnswerAdmissionCommand answerCommand(String clientRequestId) {
        return new AgentWorkflowAnswerAdmissionCommand(
                "user-1", "thread-1", clientRequestId, 1, "run-1",
                "question-1", "checkpoint-1", 0, Map.of("decision", "APPROVE"));
    }
}
