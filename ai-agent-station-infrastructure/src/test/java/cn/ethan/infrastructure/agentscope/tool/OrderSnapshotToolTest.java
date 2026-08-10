package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单快照工具测试：验证运行时用户授权、只读声明和脱敏输出边界。
 *
 * @author ethan
 * @date 2026-08-06
 */
class OrderSnapshotToolTest {

    @Test
    void returnsSanitizedSnapshotForRuntimeUser() {
        AtomicReference<String> queriedUserId = new AtomicReference<>();
        OrderSnapshotTool tool = new OrderSnapshotTool((orderId, userId) -> {
            queriedUserId.set(userId);
            return OrderLookupResultModel.found(new OrderSnapshotModel(
                    orderId,
                    userId,
                    OrderStatusEnum.SHIPPED,
                    null,
                    Instant.parse("2026-08-01T00:00:00Z"),
                    Instant.parse("2026-08-08T00:00:00Z"),
                    Instant.parse("2026-08-05T00:00:00Z"),
                    "IN_TRANSIT"
            ));
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1", "ORDER-001")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals("user-1", queriedUserId.get());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(content.getText().contains("ORDER_FOUND"));
        assertFalse(content.getText().contains("user-1"));
    }

    @Test
    void hidesOrderExistenceForAccessDenied() {
        OrderSnapshotTool tool = new OrderSnapshotTool(
                (orderId, userId) -> OrderLookupResultModel.denied()
        );

        ToolResultBlock result = tool.callAsync(parameter("user-1", "ORDER-PRIVATE")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(content.getText().contains("ORDER_UNAVAILABLE"));
    }

    @Test
    void convertsGatewayFailureToStableToolError() {
        OrderSnapshotTool tool = new OrderSnapshotTool((orderId, userId) -> {
            throw new IllegalStateException("downstream failure");
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1", "ORDER-PAID-001")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(content.getText().endsWith("ORDER_TEMPORARY_FAILURE"));
    }

    private ToolCallParam parameter(String userId, String orderId) {
        CancellationToken token = new CancellationToken();
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId("session-1")
                .put(CancellationToken.class, token)
                .build();
        Map<String, Object> input = Map.of("orderId", orderId);
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id("tool-call-1")
                        .name(OrderSnapshotTool.NAME)
                        .input(input)
                        .build())
                .input(input)
                .runtimeContext(context)
                .build();
    }
}
