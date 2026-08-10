package cn.ethan.infrastructure.agentscope.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可逆确认探针测试：验证探针始终请求确认且执行副作用可观测。
 *
 * @author ethan
 * @date 2026-08-10
 */
class ReversibleConfirmationProbeToolTest {

    @Test
    void alwaysAsksBeforeExecutingAndCountsOnlyActualCalls() {
        ReversibleConfirmationProbeTool tool = new ReversibleConfirmationProbeTool();

        assertEquals(PermissionBehavior.ASK, tool.checkPermissions(Map.of("label", "test"), null)
                .block().getBehavior());
        assertFalse(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals(0, tool.executions());

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder().id("tool-call-1")
                        .name(ReversibleConfirmationProbeTool.NAME).input(Map.of("label", "test")).build())
                .input(Map.of("label", "test"))
                .build()).block();

        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertEquals(ReversibleConfirmationProbeTool.NAME, result.getName());
        assertEquals(1, tool.executions());
    }
}
