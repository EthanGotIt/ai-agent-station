package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.ToolEvidence;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.Map;

/**
 * 共享的 Stub 工具端口，按仓库中的订单模拟 query_order 调用。
 */
public final class StubAfterSalesToolPort implements IAfterSalesToolPort {

    private final InMemoryAfterSalesRepository repository;

    public StubAfterSalesToolPort(InMemoryAfterSalesRepository repository) {
        this.repository = repository;
    }

    @Override
    public AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request, AfterSalesToolContext context) {
        String orderId = extractOrderId(request.argumentsJson());
        var order = repository.orders.get(orderId);
        if (order == null) {
            return AfterSalesToolResult.failure("", "ORDER_NOT_FOUND", "订单不存在");
        }
        String ownerId = context.userId().equals(order.ownerId()) ? context.userId() : "__FOREIGN__";
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("orderId", order.orderId());
        evidence.put("ownerId", ownerId);
        evidence.put("status", order.status());
        if (order.daysSinceDelivery() != null) {
            evidence.put("daysSinceDelivery", order.daysSinceDelivery());
        }
        return AfterSalesToolResult.success(JSON.toJSONString(evidence), new ToolEvidence("query_order", evidence));
    }

    private String extractOrderId(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        String trimmed = argumentsJson.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            JSONObject arguments = JSON.parseObject(trimmed);
            return arguments == null ? null : arguments.getString("orderId");
        }
        return trimmed;
    }
}
