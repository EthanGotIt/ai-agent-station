package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.agent.support.CancellationToken;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退款状态工具测试：验证只读声明与运行时用户归属边界。
 *
 * @author ethan
 * @date 2026-08-07
 */
class RefundStatusToolTest {

    @Test
    void queriesRefundForRuntimeUserOnly() {
        AtomicReference<String> queriedUserId = new AtomicReference<>();
        RefundStatusTool tool = new RefundStatusTool(new RefundCommandGateway() {
            @Override
            public RefundCommandResultModel create(RefundCommandModel command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
                queriedUserId.set(userId);
                return Optional.of(new RefundCommandResultModel(
                        "refund-1", orderId, userId, "ACCEPTED", new BigDecimal("99.00"), "CNY",
                        Instant.parse("2026-08-07T00:00:00Z")
                ));
            }
        });

        ToolResultBlock result = tool.callAsync(parameter("user-1", "ORDER-PAID-001")).block();
        TextBlock content = (TextBlock) result.getOutput().get(0);

        assertTrue(tool.isReadOnly());
        assertTrue(tool.isConcurrencySafe());
        assertFalse(tool.isExternalTool());
        assertEquals("user-1", queriedUserId.get());
        assertEquals(ToolResultState.SUCCESS, result.getState());
        assertTrue(content.getText().contains("REFUND_FOUND"));
        assertFalse(content.getText().contains("user-1"));
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
                        .name(RefundStatusTool.NAME)
                        .input(input)
                        .build())
                .input(input)
                .runtimeContext(context)
                .build();
    }
}
