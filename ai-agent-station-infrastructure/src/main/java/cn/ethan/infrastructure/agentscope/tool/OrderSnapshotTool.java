package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderItemModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.port.OrderGateway;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 订单快照工具：仅按运行时用户上下文读取一笔订单的脱敏诊断数据。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class OrderSnapshotTool extends ToolBase {

    public static final String NAME = "get_order_snapshot";

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile(
            "(?i)^ORDER-[A-Z0-9][A-Z0-9-]{0,62}$"
    );
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "orderId", Map.of(
                            "type", "string",
                            "description", "用户明确提供的订单号，例如 ORDER-001"
                    )
            ),
            "required", List.of("orderId"),
            "additionalProperties", false
    );

    private final OrderGateway gateway;

    public OrderSnapshotTool(OrderGateway gateway) {
        super(ToolBase.builder()
                .name(NAME)
                .description("按当前登录用户查询单笔订单的只读快照，用于订单状态与履约分析。")
                .inputSchema(INPUT_SCHEMA)
                .readOnly(true)
                .concurrencySafe(true)
                .externalTool(false));
        this.gateway = gateway;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(
            Map<String, Object> input,
            PermissionContextState context
    ) {
        return Mono.just(isValidOrderId(input == null ? null : input.get("orderId"))
                ? PermissionDecision.allow("订单号格式有效")
                : PermissionDecision.deny("订单号格式无效"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.fromSupplier(() -> {
            RuntimeContext context = parameter.getRuntimeContext();
            CancellationToken token = context == null
                    ? null
                    : context.get(CancellationToken.class);
            throwIfCancelled(token);

            String userId = context == null ? null : context.getUserId();
            String orderId = normalizeOrderId(parameter.getInput().get("orderId"));
            if (userId == null || userId.isBlank() || orderId == null) {
                return result(parameter, error("ORDER_UNAVAILABLE"));
            }

            OrderLookupResultModel lookup;
            try {
                lookup = gateway.findOrder(orderId, userId);
            } catch (RuntimeException gatewayFailure) {
                return result(parameter, error("ORDER_TEMPORARY_FAILURE"));
            }
            throwIfCancelled(token);
            try {
                return result(parameter, switch (lookup.status()) {
                    case FOUND -> ToolResultBlock.text(formatSnapshot(lookup.order()))
                            .withState(ToolResultState.SUCCESS);
                    case NOT_FOUND, ACCESS_DENIED -> error("ORDER_UNAVAILABLE");
                    case TEMPORARY_FAILURE -> error("ORDER_TEMPORARY_FAILURE");
                });
            } catch (RuntimeException gatewayFailure) {
                return result(parameter, error("ORDER_TEMPORARY_FAILURE"));
            }
        });
    }

    private ToolResultBlock result(ToolCallParam parameter, ToolResultBlock result) {
        return result.withIdAndName(
                parameter.getToolUseBlock().getId(),
                NAME
        );
    }

    private ToolResultBlock error(String code) {
        return ToolResultBlock.error(code).withState(ToolResultState.ERROR);
    }

    private String formatSnapshot(OrderSnapshotModel snapshot) {
        List<OrderItemModel> items = gateway.findItems(snapshot.orderId(), snapshot.userId());
        return "ORDER_FOUND"
                + " orderId=" + safe(snapshot.orderId())
                + " status=" + snapshot.status().name()
                + " paidAmount=" + (snapshot.paidAmount() == null ? "UNKNOWN" : snapshot.paidAmount())
                + " currency=" + safe(snapshot.currency())
                + " createdAt=" + formatInstant(snapshot.createdAt())
                + " expectedDeliveryAt=" + formatInstant(snapshot.expectedDeliveryAt())
                + " lastLogisticsAt=" + formatInstant(snapshot.lastLogisticsAt())
                + " logisticsStatus=" + safe(snapshot.logisticsStatus())
                + " items=" + items.stream().limit(10)
                .map(item -> safe(item.productName()) + "x" + item.quantity())
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private String formatInstant(Instant value) {
        return value == null ? "UNKNOWN" : value.toString();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return normalized.substring(0, Math.min(normalized.length(), 128));
    }

    private boolean isValidOrderId(Object value) {
        return normalizeOrderId(value) != null;
    }

    private String normalizeOrderId(Object value) {
        if (!(value instanceof String raw)) {
            return null;
        }
        String candidate = raw.strip().toUpperCase();
        return ORDER_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private void throwIfCancelled(CancellationToken token) {
        if (token != null) {
            token.throwIfCancelled();
        }
    }
}
