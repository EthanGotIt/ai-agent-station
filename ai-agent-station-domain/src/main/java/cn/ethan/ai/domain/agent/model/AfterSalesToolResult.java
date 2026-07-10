package cn.ethan.ai.domain.agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

public record AfterSalesToolResult(boolean success,
                                   String outputJson,
                                   ToolEvidence evidence,
                                   String errorType,
                                   String errorMessage) {

    public static AfterSalesToolResult success(String outputJson, ToolEvidence evidence) {
        return new AfterSalesToolResult(true, outputJson, evidence, null, null);
    }

    @Deprecated(forRemoval = true)
    public static AfterSalesToolResult success(String outputJson, AfterSalesOrderSnapshot order) {
        if (order == null) {
            return failure(outputJson, "TOOL_FAILURE", "订单证据为空");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("orderId", order.orderId());
        fields.put("ownerId", order.ownerId());
        fields.put("status", order.status());
        if (order.daysSinceDelivery() != null) {
            fields.put("daysSinceDelivery", order.daysSinceDelivery());
        }
        return success(outputJson, new ToolEvidence("query_order", fields));
    }

    public static AfterSalesToolResult failure(String outputJson, String errorType, String errorMessage) {
        return new AfterSalesToolResult(false, outputJson, null, errorType, errorMessage);
    }

    @Deprecated(forRemoval = true)
    public AfterSalesOrderSnapshot order() {
        if (evidence == null || !"query_order".equals(evidence.toolName())) {
            return null;
        }
        Map<String, Object> fields = evidence.fields();
        Object days = fields.get("daysSinceDelivery");
        Integer daysSinceDelivery = days instanceof Number number ? number.intValue() : null;
        return new AfterSalesOrderSnapshot(
                text(fields.get("orderId")), text(fields.get("ownerId")), text(fields.get("status")), daysSinceDelivery);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
