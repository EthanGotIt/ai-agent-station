package cn.ethan.core.agent.thread;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证 Turn 生命周期版本的单调性和非法值边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentTurnVersionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void lifecycleTransitionsAdvanceExactlyOneVersion() {
        AgentTurnModel queued = turn();

        AgentTurnModel active = queued.active(NOW.plusSeconds(1));
        AgentTurnModel waiting = active.workflow("run-1", AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION);
        AgentTurnModel completed = waiting.terminal(AgentTurnStatusEnum.COMPLETED, null, NOW.plusSeconds(2));

        assertEquals(0L, queued.version());
        assertEquals(1L, active.version());
        assertEquals(2L, waiting.version());
        assertEquals(3L, completed.version());
    }

    @Test
    void negativeVersionIsRejectedInsteadOfNormalized() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "message",
                AgentTurnStatusEnum.QUEUED, 0, null, null, NOW, null, null, null, -1L));
    }

    private AgentTurnModel turn() {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "message",
                AgentTurnStatusEnum.QUEUED, 0, null, null, NOW, null, null);
    }
}
