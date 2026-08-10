package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.model.LogisticsEventModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物流轨迹工具测试：验证用户隔离、输出预算和异常降级。
 *
 * @author ethan
 * @date 2026-08-10
 */
class LogisticsTraceToolTest {

    @Test
    void readsRuntimeUserAndCapsTraceOutput() {
        AtomicReference<String> userId = new AtomicReference<>();
        LogisticsTraceTool tool = new LogisticsTraceTool((orderId, currentUserId) -> {
            userId.set(currentUserId);
            return java.util.stream.IntStream.range(0, 30).mapToObj(index -> new LogisticsEventModel(
                    "event-" + index, orderId, "IN_TRANSIT", "Shanghai", "x".repeat(400),
                    Instant.parse("2026-08-10T00:00:00Z").plusSeconds(index)
            )).toList();
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals("user-1", userId.get());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(content.getText().startsWith("LOGISTICS_TRACE"));
        assertTrue(content.getText().length() <= 4_000);
        assertFalse(content.getText().contains("user-1"));
    }

    @Test
    void convertsGatewayFailureToStableToolError() {
        LogisticsTraceTool tool = new LogisticsTraceTool((orderId, userId) -> {
            throw new IllegalStateException("downstream failure");
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(content.getText().endsWith("LOGISTICS_TEMPORARY_FAILURE"));
    }

    private ToolCallParam parameter(String userId) {
        RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId("session-1")
                .put(CancellationToken.class, new CancellationToken()).build();
        Map<String, Object> input = Map.of("orderId", "ORDER-SHIPPED-STALLED-001");
        return ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder()
                        .id("tool-call-1").name(LogisticsTraceTool.NAME).input(input).build())
                .input(input).runtimeContext(context).build();
    }
}
