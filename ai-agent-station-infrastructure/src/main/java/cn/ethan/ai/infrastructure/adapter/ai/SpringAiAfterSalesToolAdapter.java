package cn.ethan.ai.infrastructure.adapter.ai;

import cn.ethan.ai.domain.agent.model.AfterSalesLogisticsSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundHistorySnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolCapability;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.ToolEvidence;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.type.TypeReference;

/**
 * 将已通过领域 Policy 的计划步骤执行为只读 commerce 证据。
 *
 * <p>规划模型只在 {@link RefundPlanningAgent} 中调用。本适配器不再二次调用模型提取工具参数。</p>
 */
@Service
public class SpringAiAfterSalesToolAdapter implements IAfterSalesToolPort {

    private final IOrderGateway orderGateway;
    private final AfterSalesRuntimeMetrics metrics;
    private final Set<AfterSalesToolCapability> supportedTools;
    private final AfterSalesJsonCodec jsonCodec;

    public SpringAiAfterSalesToolAdapter(IOrderGateway orderGateway,
                                         AfterSalesRuntimeMetrics metrics,
                                         @Value("${ai-agent.after-sales.evidence-tools:query_order}") String configuredTools) {
        this(orderGateway, metrics, configuredTools, AfterSalesJsonCodec.defaultCodec());
    }

    @Autowired
    public SpringAiAfterSalesToolAdapter(IOrderGateway orderGateway,
                                         AfterSalesRuntimeMetrics metrics,
                                         @Value("${ai-agent.after-sales.evidence-tools:query_order}") String configuredTools,
                                         AfterSalesJsonCodec jsonCodec) {
        this.orderGateway = orderGateway;
        this.metrics = metrics;
        this.supportedTools = parseCapabilities(configuredTools);
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Set<AfterSalesToolCapability> supportedTools() {
        return supportedTools;
    }

    @Override
    public AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request, AfterSalesToolContext context) {
        long startedAt = System.nanoTime();
        AfterSalesToolResult result;
        try {
            if (request == null || context == null || context.userId() == null || context.userId().isBlank()) {
                result = AfterSalesToolResult.failure("", "TOOL_ARGUMENT_INVALID", "缺少可信工具上下文");
            } else {
                AfterSalesToolCapability capability = AfterSalesToolCapability.fromToolName(request.toolName());
                if (capability == null || !supportedTools.contains(capability)) {
                    result = AfterSalesToolResult.failure("", "TOOL_NOT_ALLOWED", "当前运行时不允许该工具");
                } else {
                    String orderId = requiredOrderId(request.argumentsJson());
                    result = switch (capability) {
                        case QUERY_ORDER -> queryOrder(orderId, context.userId());
                        case QUERY_LOGISTICS -> queryLogistics(orderId, context.userId());
                        case QUERY_REFUND_HISTORY -> queryRefundHistory(orderId, context.userId());
                    };
                }
            }
        } catch (IllegalArgumentException error) {
            result = AfterSalesToolResult.failure("", "TOOL_ARGUMENT_INVALID", error.getMessage());
        } catch (Exception error) {
            result = AfterSalesToolResult.failure("", classifyException(error), "只读证据查询失败");
        }
        metrics.recordEvidenceTool(request == null ? "unknown" : request.toolName(),
                System.nanoTime() - startedAt, result.success() ? "success" : result.errorType());
        return result;
    }

    private AfterSalesToolResult queryOrder(String orderId, String userId) {
        return orderGateway.findOrder(orderId, userId)
                .map(order -> order.ownerId().equals("__FOREIGN__")
                        ? AfterSalesToolResult.failure("", "ACCESS_DENIED", "没有访问该订单的权限")
                        : success("query_order", orderPayload(order)))
                .orElseGet(() -> AfterSalesToolResult.failure("", "ORDER_NOT_FOUND", "订单不存在"));
    }

    private AfterSalesToolResult queryLogistics(String orderId, String userId) {
        return orderGateway.findLogistics(orderId, userId)
                .map(snapshot -> success("query_logistics", logisticsPayload(snapshot)))
                .orElseGet(() -> AfterSalesToolResult.failure("", "EVIDENCE_UNAVAILABLE", "物流证据不可用"));
    }

    private AfterSalesToolResult queryRefundHistory(String orderId, String userId) {
        return orderGateway.findRefundHistory(orderId, userId)
                .map(snapshot -> success("query_refund_history", refundHistoryPayload(snapshot)))
                .orElseGet(() -> AfterSalesToolResult.failure("", "EVIDENCE_UNAVAILABLE", "退款历史不可用"));
    }

    private AfterSalesToolResult success(String toolName, Map<String, Object> payload) {
        return AfterSalesToolResult.success(jsonCodec.write(payload, "序列化工具证据"),
                new ToolEvidence(toolName, payload));
    }

    private Map<String, Object> orderPayload(AfterSalesOrderSnapshot order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.orderId());
        payload.put("ownerId", order.ownerId());
        payload.put("status", order.status());
        if (order.daysSinceDelivery() != null) {
            payload.put("daysSinceDelivery", order.daysSinceDelivery());
        }
        return payload;
    }

    private Map<String, Object> logisticsPayload(AfterSalesLogisticsSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", snapshot.orderId());
        payload.put("deliveryStatus", snapshot.deliveryStatus());
        payload.put("returnStatus", snapshot.returnStatus());
        if (snapshot.deliveredAt() != null) {
            payload.put("deliveredAt", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(snapshot.deliveredAt()));
        }
        return payload;
    }

    private Map<String, Object> refundHistoryPayload(AfterSalesRefundHistorySnapshot snapshot) {
        return Map.of(
                "orderId", snapshot.orderId(),
                "activeRefund", snapshot.activeRefund(),
                "completedRefundCount", snapshot.completedRefundCount(),
                "latestRefundStatus", snapshot.latestRefundStatus()
        );
    }

    private String requiredOrderId(String argumentsJson) {
        Map<String, Object> input = jsonCodec.read(argumentsJson, new TypeReference<>() {
        }, "解析工具参数");
        Object orderIdValue = input == null ? null : input.get("orderId");
        String orderId = orderIdValue instanceof String value ? value : null;
        if (orderId == null || !orderId.matches("[A-Za-z0-9_-]{3,64}")) {
            throw new IllegalArgumentException("orderId 格式非法");
        }
        if (input.size() != 1) {
            throw new IllegalArgumentException("工具参数只允许 orderId");
        }
        return orderId;
    }

    private Set<AfterSalesToolCapability> parseCapabilities(String configuredTools) {
        Set<AfterSalesToolCapability> capabilities = EnumSet.noneOf(AfterSalesToolCapability.class);
        String[] names = configuredTools == null ? new String[0] : configuredTools.split(",");
        for (String name : names) {
            AfterSalesToolCapability capability = AfterSalesToolCapability.fromToolName(name.trim());
            if (capability != null) {
                capabilities.add(capability);
            }
        }
        return capabilities.isEmpty() ? Set.of(AfterSalesToolCapability.QUERY_ORDER) : Set.copyOf(capabilities);
    }

    private String classifyException(Exception error) {
        String text = String.valueOf(error.getMessage()).toLowerCase();
        if (text.contains("timeout")) {
            return "TIMEOUT";
        }
        if (text.contains("403") || text.contains("access")) {
            return "ACCESS_DENIED";
        }
        return "TEMPORARY_UNAVAILABLE";
    }
}
