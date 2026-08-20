package cn.ethan.core.commerce.order;

import java.util.Locale;

/**
 * 订单状态枚举：统一内部订单快照可识别的履约状态。
 *
 * @author ethan
 * @date 2026-08-06
 */
public enum OrderStatusEnum {
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED,
    REFUNDED,
    UNKNOWN;

    public static OrderStatusEnum fromValue(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unsupportedStatus) {
            return UNKNOWN;
        }
    }
}
