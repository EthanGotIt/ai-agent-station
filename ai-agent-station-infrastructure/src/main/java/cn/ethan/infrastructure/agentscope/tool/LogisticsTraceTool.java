package cn.ethan.infrastructure.agentscope.tool;

import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.model.LogisticsEventModel;
import cn.ethan.core.order.port.LogisticsGateway;
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
 * 物流轨迹工具：只返回当前用户可访问订单的时间正序物流事件。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class LogisticsTraceTool extends ToolBase {

    public static final String NAME = "get_logistics_trace";
    private static final int MAX_EVENTS = 20;
    private static final int MAX_OUTPUT_CHARACTERS = 4_000;
    private static final Pattern ORDER_ID = Pattern.compile("(?i)^ORDER-[A-Z0-9][A-Z0-9-]{0,62}$");
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object", "properties", Map.of("orderId", Map.of("type", "string")),
            "required", List.of("orderId"), "additionalProperties", false
    );

    private final LogisticsGateway gateway;

    public LogisticsTraceTool(LogisticsGateway gateway) {
        super(ToolBase.builder().name(NAME)
                .description("查询当前登录用户某笔订单的只读物流时间线。")
                .inputSchema(INPUT_SCHEMA).readOnly(true).concurrencySafe(true).externalTool(false));
        this.gateway = gateway;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> input, PermissionContextState context) {
        return Mono.just(orderId(input == null ? null : input.get("orderId")) == null
                ? PermissionDecision.deny("订单号格式无效") : PermissionDecision.allow("物流查询为只读操作"));
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
            String orderId = orderId(parameter.getInput().get("orderId"));
            if (userId == null || userId.isBlank() || orderId == null) {
                return result(parameter, ToolResultBlock.error("LOGISTICS_UNAVAILABLE").withState(ToolResultState.ERROR));
            }
            try {
                List<LogisticsEventModel> events = gateway.findTrace(orderId, userId);
                String text = events.isEmpty() ? "LOGISTICS_NOT_FOUND" : events.stream().limit(MAX_EVENTS)
                        .map(event -> event.occurredAt() + "|" + safe(event.status(), 64) + "|"
                                + safe(event.location(), 128) + "|" + safe(event.description(), 256))
                        .collect(java.util.stream.Collectors.joining(";", "LOGISTICS_TRACE ", ""));
                return result(parameter, ToolResultBlock.text(limitOutput(text)).withState(ToolResultState.SUCCESS));
            } catch (RuntimeException gatewayFailure) {
                return result(parameter, ToolResultBlock.error("LOGISTICS_TEMPORARY_FAILURE")
                        .withState(ToolResultState.ERROR));
            }
        });
    }

    private ToolResultBlock result(ToolCallParam parameter, ToolResultBlock result) {
        return result.withIdAndName(parameter.getToolUseBlock().getId(), NAME);
    }

    private String orderId(Object input) {
        if (!(input instanceof String value)) {
            return null;
        }
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        return ORDER_ID.matcher(normalized).matches() ? normalized : null;
    }

    private String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.replaceAll("[\\r\\n;|]", " ");
        return normalized.substring(0, Math.min(normalized.length(), maxLength));
    }

    private String limitOutput(String value) {
        return value.length() <= MAX_OUTPUT_CHARACTERS ? value : value.substring(0, MAX_OUTPUT_CHARACTERS);
    }
}
