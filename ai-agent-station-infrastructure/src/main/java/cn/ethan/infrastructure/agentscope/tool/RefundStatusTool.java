package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.agent.support.CancellationToken;
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
import java.util.regex.Pattern;

/**
 * 退款状态工具：仅按当前运行时用户上下文读取该用户已有的退款记录。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class RefundStatusTool extends ToolBase {

    public static final String NAME = "get_refund_status";

    private static final Pattern ORDER_ID_PATTERN = Pattern.compile(
            "(?i)^ORDER-[A-Z0-9][A-Z0-9-]{0,62}$"
    );
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "orderId", Map.of(
                            "type", "string",
                            "description", "用户明确提供的订单号，例如 ORDER-PAID-001"
                    )
            ),
            "required", List.of("orderId"),
            "additionalProperties", false
    );

    private final RefundCommandGateway gateway;

    public RefundStatusTool(RefundCommandGateway gateway) {
        super(ToolBase.builder()
                .name(NAME)
                .description("按当前登录用户查询订单已有退款申请的只读状态。")
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
            CancellationToken token = context == null ? null : context.get(CancellationToken.class);
            throwIfCancelled(token);
            String userId = context == null ? null : context.getUserId();
            String orderId = normalizeOrderId(parameter.getInput().get("orderId"));
            if (userId == null || userId.isBlank() || orderId == null) {
                return result(parameter, error("REFUND_UNAVAILABLE"));
            }
            return result(parameter, gateway.findByOrder(orderId, userId)
                    .map(this::format)
                    .orElseGet(() -> ToolResultBlock.text("REFUND_NOT_FOUND")
                            .withState(ToolResultState.SUCCESS)));
        });
    }

    private ToolResultBlock format(RefundCommandResultModel refund) {
        return ToolResultBlock.text(
                "REFUND_FOUND refundId=" + safe(refund.refundId())
                        + " orderId=" + safe(refund.orderId())
                        + " status=" + safe(refund.status())
                        + " amount=" + refund.amount().toPlainString()
                        + " currency=" + safe(refund.currency())
        ).withState(ToolResultState.SUCCESS);
    }

    private ToolResultBlock result(ToolCallParam parameter, ToolResultBlock result) {
        return result.withIdAndName(parameter.getToolUseBlock().getId(), NAME);
    }

    private ToolResultBlock error(String code) {
        return ToolResultBlock.error(code).withState(ToolResultState.ERROR);
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

    private String safe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private void throwIfCancelled(CancellationToken token) {
        if (token != null) {
            token.throwIfCancelled();
        }
    }
}
