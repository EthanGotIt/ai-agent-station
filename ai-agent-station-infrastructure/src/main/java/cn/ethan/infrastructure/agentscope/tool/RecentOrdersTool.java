package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.port.OrderGateway;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 近期订单工具：仅从运行时用户上下文读取有限数量的近期订单。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class RecentOrdersTool extends ToolBase {

    public static final String NAME = "list_recent_orders";

    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("limit", Map.of("type", "integer", "minimum", 1, "maximum", 10)),
            "additionalProperties", false
    );

    private final OrderGateway gateway;

    public RecentOrdersTool(OrderGateway gateway) {
        super(ToolBase.builder()
                .name(NAME)
                .description("查询当前登录用户近期订单的只读摘要；不要用它读取其他用户订单。")
                .inputSchema(INPUT_SCHEMA)
                .readOnly(true)
                .concurrencySafe(true)
                .externalTool(false));
        this.gateway = gateway;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(PermissionDecision.allow("近期订单查询为只读操作"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.fromSupplier(() -> {
            RuntimeContext context = parameter.getRuntimeContext();
            CancellationToken token = context == null ? null : context.get(CancellationToken.class);
            if (token != null) {
                token.throwIfCancelled();
            }
            String userId = context == null ? null : context.getUserId();
            if (userId == null || userId.isBlank()) {
                return result(parameter, ToolResultBlock.error("ORDERS_UNAVAILABLE").withState(ToolResultState.ERROR));
            }
            int limit = limit(parameter.getInput().get("limit"));
            try {
                List<RecentOrderModel> orders = gateway.listRecentOrders(userId, limit);
                String content = orders.isEmpty() ? "RECENT_ORDERS_EMPTY" : orders.stream().limit(10)
                        .map(order -> safe(order.orderId()) + ":" + order.status().name()
                                + ":" + (order.createdAt() == null ? "UNKNOWN" : order.createdAt()))
                        .collect(java.util.stream.Collectors.joining(";", "RECENT_ORDERS ", ""));
                return result(parameter, ToolResultBlock.text(content).withState(ToolResultState.SUCCESS));
            } catch (RuntimeException gatewayFailure) {
                return result(parameter, ToolResultBlock.error("RECENT_ORDERS_TEMPORARY_FAILURE")
                        .withState(ToolResultState.ERROR));
            }
        });
    }

    private int limit(Object input) {
        if (input instanceof Number number) {
            return Math.max(1, Math.min(number.intValue(), 10));
        }
        return 5;
    }

    private ToolResultBlock result(ToolCallParam parameter, ToolResultBlock result) {
        return result.withIdAndName(parameter.getToolUseBlock().getId(), NAME);
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }
}
