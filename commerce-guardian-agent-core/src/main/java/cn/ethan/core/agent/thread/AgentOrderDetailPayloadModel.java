package cn.ethan.core.agent.thread;

import java.math.BigDecimal;

/**
 * 类型职责：表达单个订单的完整受控事实，保持详情 data 与历史字段平铺兼容。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentOrderDetailPayloadModel(
        String orderId,
        String status,
        Integer daysSinceDelivery,
        String createdAt,
        String expectedDeliveryAt,
        String lastLogisticsAt,
        String logisticsStatus,
        BigDecimal paidAmount,
        String currency,
        String itemSummary,
        String visibility
) implements AgentItemPayloadValue {

    public AgentOrderDetailPayloadModel {
        orderId = required(orderId, "orderId");
        status = required(status, "status");
        createdAt = optional(createdAt);
        expectedDeliveryAt = optional(expectedDeliveryAt);
        lastLogisticsAt = optional(lastLogisticsAt);
        logisticsStatus = optional(logisticsStatus);
        currency = optional(currency);
        itemSummary = optional(itemSummary);
        visibility = visibility == null || visibility.isBlank() ? "ACTIVE" : visibility.trim();
        if (daysSinceDelivery != null && daysSinceDelivery < 0) {
            throw new IllegalArgumentException("daysSinceDelivery 不能为负数");
        }
        if (paidAmount != null && paidAmount.signum() < 0) {
            throw new IllegalArgumentException("paidAmount 不能为负数");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.ORDER_DETAIL;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
