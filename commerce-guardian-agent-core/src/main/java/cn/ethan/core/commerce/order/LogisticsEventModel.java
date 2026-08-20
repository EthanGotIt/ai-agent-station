package cn.ethan.core.commerce.order;

import java.time.Instant;

/**
 * 物流事件模型：订单追踪和履约诊断只消费经过归属校验后的时间线。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record LogisticsEventModel(
        String eventId,
        String orderId,
        String status,
        String location,
        String description,
        Instant occurredAt
) {

    public LogisticsEventModel {
        if (isBlank(eventId) || isBlank(orderId) || isBlank(status) || isBlank(description)
                || occurredAt == null) {
            throw new IllegalArgumentException("logistics event is invalid");
        }
        location = location == null ? "" : location.strip();
        description = description.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
