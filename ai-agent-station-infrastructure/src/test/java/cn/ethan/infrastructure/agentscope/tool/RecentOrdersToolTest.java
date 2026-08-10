package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.port.OrderGateway;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 近期订单工具测试：验证 RuntimeContext 隔离、输入限额和临时失败语义。
 *
 * @author ethan
 * @date 2026-08-10
 */
class RecentOrdersToolTest {

    @Test
    void readsOnlyRuntimeUserAndBoundsRequestedLimit() {
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicInteger limit = new AtomicInteger();
        RecentOrdersTool tool = new RecentOrdersTool(new OrderGateway() {
            @Override
            public OrderLookupResultModel findOrder(String orderId, String currentUserId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<RecentOrderModel> listRecentOrders(String currentUserId, int currentLimit) {
                userId.set(currentUserId);
                limit.set(currentLimit);
                return List.of(new RecentOrderModel(
                        "ORDER-PAID-001", OrderStatusEnum.PAID, Instant.parse("2026-08-10T00:00:00Z")
                ));
            }
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1", 99)).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals("user-1", userId.get());
        assertEquals(10, limit.get());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(content.getText().contains("RECENT_ORDERS"));
        assertFalse(content.getText().contains("user-1"));
    }

    @Test
    void convertsGatewayFailureToStableToolError() {
        RecentOrdersTool tool = new RecentOrdersTool(new OrderGateway() {
            @Override
            public OrderLookupResultModel findOrder(String orderId, String userId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<RecentOrderModel> listRecentOrders(String userId, int limit) {
                throw new IllegalStateException("downstream failure");
            }
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1", 5)).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertEquals(ToolResultState.ERROR, result.getState());
        assertTrue(content.getText().endsWith("RECENT_ORDERS_TEMPORARY_FAILURE"));
    }

    private ToolCallParam parameter(String userId, int limit) {
        RuntimeContext context = RuntimeContext.builder().userId(userId).sessionId("session-1")
                .put(CancellationToken.class, new CancellationToken()).build();
        Map<String, Object> input = Map.of("limit", limit);
        return ToolCallParam.builder().toolUseBlock(ToolUseBlock.builder()
                        .id("tool-call-1").name(RecentOrdersTool.NAME).input(input).build())
                .input(input).runtimeContext(context).build();
    }
}
