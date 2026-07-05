package cn.ethan.ai.test.fixture;

import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.policy.AfterSalesToolContractValidator;
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
    public AfterSalesToolRequest proposeOrderQuery(String userMessage, String userId, String sessionId,
                                                   String orderIdHint, String refundReason, String correction) {
        if (orderIdHint == null || orderIdHint.isBlank()) {
            throw new IllegalArgumentException("ORDER_ID_REQUIRED");
        }
        return new AfterSalesToolRequest("call-1", AfterSalesToolContractValidator.QUERY_ORDER_TOOL,
                JSON.toJSONString(Map.of("orderId", orderIdHint)));
    }

    @Override
    public AfterSalesToolResult executeOrderQuery(AfterSalesToolRequest request,
                                                  String userId,
                                                  String userMessage) {
        String orderId = extractOrderId(request.argumentsJson());
        AfterSalesOrderSnapshot order = repository.orders.get(orderId);
        if (order == null) {
            return AfterSalesToolResult.failure("", "ORDER_NOT_FOUND", "订单不存在");
        }
        String ownerId = userId.equals(order.ownerId()) ? userId : "__FOREIGN__";
        AfterSalesOrderSnapshot sanitized = new AfterSalesOrderSnapshot(
                order.orderId(), ownerId, order.status(), order.daysSinceDelivery());
        return AfterSalesToolResult.success("{}", sanitized);
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
