package cn.ethan.core.agent.workflow;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证 WorkflowRun 的可恢复人工重试状态和不可变终态边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentWorkflowRunStateTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void manualRetryRequiredCanReturnToExternalActionAndComplete() {
        AgentWorkflowRunModel manual = run(AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED, 1);

        AgentWorkflowRunModel waiting = manual.status(
                AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, NOW.plusSeconds(1));
        AgentWorkflowRunModel completed = waiting.status(
                AgentWorkflowStatusEnum.COMPLETED, NOW.plusSeconds(2));

        assertEquals(2L, waiting.version());
        assertEquals(AgentWorkflowStatusEnum.COMPLETED, completed.status());
    }

    @Test
    void immutableTerminalCannotBeRewritten() {
        AgentWorkflowRunModel completed = run(AgentWorkflowStatusEnum.COMPLETED, 3);

        assertThrows(IllegalStateException.class,
                () -> completed.status(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, NOW));
    }

    @Test
    void sameStatusCannotAdvanceVersionWithoutAStateTransition() {
        AgentWorkflowRunModel waiting = run(AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0);

        assertThrows(IllegalStateException.class,
                () -> waiting.status(AgentWorkflowStatusEnum.WAITING_USER_INPUT, NOW));
    }

    @Test
    void negativeVersionIsRejectedAtPersistenceBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> run(AgentWorkflowStatusEnum.WAITING_USER_INPUT, -1));
    }

    private AgentWorkflowRunModel run(AgentWorkflowStatusEnum status, long version) {
        return new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                status, version, NOW, NOW);
    }
}
