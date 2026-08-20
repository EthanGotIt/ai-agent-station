package cn.ethan.core.commerce.order;


import java.time.Instant;

/**
 * 近期订单模型：仅提供补全订单号所需的最小安全摘要。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record RecentOrderModel(
        String orderId,
        OrderStatusEnum status,
        Instant createdAt
) {

    public RecentOrderModel {
        if (orderId == null || orderId.isBlank() || status == null) {
            throw new IllegalArgumentException("recent order is invalid");
        }
        orderId = orderId.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
