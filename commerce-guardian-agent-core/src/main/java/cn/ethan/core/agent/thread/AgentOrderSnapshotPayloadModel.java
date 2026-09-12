package cn.ethan.core.agent.thread;

import java.math.BigDecimal;

/**
 * 类型职责：保存不含身份字段的订单事实快照，作为订单列表和详情的受控嵌套值。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentOrderSnapshotPayloadModel(
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
) {

    public AgentOrderSnapshotPayloadModel {
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
