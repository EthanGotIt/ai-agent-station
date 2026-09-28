package cn.ethan.infrastructure.agent.workflow;

import cn.ethan.core.agent.thread.AgentThreadConflictException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证旧 Workflow 排空后的受控拒绝协议。
 *
 * @author ethan
 * @date 2026-09-28
 */
class RetiredAgentWorkflowEngineTest {

    @Test
    void rejectsNewOrResumedLegacyExecutionWithStableCode() {
        RetiredAgentWorkflowEngine engine = new RetiredAgentWorkflowEngine();
        AgentThreadConflictException start = assertThrows(AgentThreadConflictException.class,
                () -> engine.start(null, null, "ORDER_SERVICE", Map.of()));
        AgentThreadConflictException resume = assertThrows(AgentThreadConflictException.class,
                () -> engine.resume(null, null, Map.of()));

        assertEquals("WORKFLOW_COMPATIBILITY_REQUIRED", start.code());
        assertEquals(start.code(), resume.code());
    }
}
