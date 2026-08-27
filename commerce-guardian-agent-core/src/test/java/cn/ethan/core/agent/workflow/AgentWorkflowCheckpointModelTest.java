package cn.ethan.core.agent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Workflow Checkpoint 契约测试：确认人工执行决策独立于 QuestionCard。
 *
 * @author ethan
 * @date 2026-08-27
 */
class AgentWorkflowCheckpointModelTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

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
    void checkpointRequiresStableFactsFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> new AgentWorkflowCheckpointModel(
                "checkpoint-1", "run-1", "thread-1", "turn-1", "user-1", "AUTHORIZE",
                "REFUND", "ORDER-1", "退款", "", 0, AgentWorkflowCheckpointStatusEnum.OPEN,
                null, NOW, null));
    }

    private AgentWorkflowCheckpointModel checkpoint() {
        return new AgentWorkflowCheckpointModel(
                "checkpoint-1", "run-1", "thread-1", "turn-1", "user-1", "AUTHORIZE",
                "REFUND", "ORDER-1", "退款订单 ORDER-1", "facts-v1", 0,
                AgentWorkflowCheckpointStatusEnum.OPEN, null, NOW, null);
    }
}
