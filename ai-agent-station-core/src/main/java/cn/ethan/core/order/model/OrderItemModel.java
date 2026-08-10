package cn.ethan.core.order.model;

import java.math.BigDecimal;

/**
 * 订单商品模型：用于订单详情卡与退款申请的可读商品摘要。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record OrderItemModel(
        String itemId,
        String orderId,
        String productName,
        int quantity,
        BigDecimal unitPrice
) {

    public OrderItemModel {
        if (isBlank(itemId) || isBlank(orderId) || isBlank(productName)
                || quantity <= 0 || unitPrice == null || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("order item is invalid");
        }
        productName = productName.strip();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
