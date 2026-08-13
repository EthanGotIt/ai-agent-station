package cn.ethan.core.after_sales.service;

import cn.ethan.core.after_sales.enums.RefundEligibilityEnum;
import cn.ethan.core.after_sales.model.RefundEligibilityModel;
import cn.ethan.core.order.model.OrderSnapshotModel;

/**
 * 退款资格服务：集中执行不依赖模型的售后退款业务规则。
 *
 * @author ethan
 * @date 2026-08-07
 */
public final class RefundEligibilityService {

    public RefundEligibilityModel evaluate(OrderSnapshotModel order) {
        if (order == null) {
            throw new IllegalArgumentException("order is required");
        }
        if (order.status().name().equals("PAID")) {
            return approved(order, "订单尚未发货，可以申请全额退款。");
        }
        if (order.status().name().equals("DELIVERED")
                && order.daysSinceDelivery() != null
                && order.daysSinceDelivery() >= 0
                && order.daysSinceDelivery() <= 7) {
            return manualReview(order, "订单签收未超过 7 天，需要人工审核后处理。");
        }
        if (order.status().name().equals("SHIPPED")) {
            return manualReview(order, "订单已发货，当前需要人工处理，暂不能自动创建退款。");
        }
        return new RefundEligibilityModel(
                RefundEligibilityEnum.REJECTED,
                "当前订单状态不支持退款申请。",
                null,
                ""
        );
    }

    private RefundEligibilityModel approved(OrderSnapshotModel order, String message) {
        if (order.paidAmount() == null || order.currency() == null || order.currency().isBlank()) {
            return new RefundEligibilityModel(
                    RefundEligibilityEnum.MANUAL_REVIEW_REQUIRED,
                    "订单金额信息不完整，需要人工处理，暂不能自动创建退款。",
                    null,
                    ""
            );
        }
        return new RefundEligibilityModel(
                RefundEligibilityEnum.APPROVED,
                message,
                order.paidAmount(),
                order.currency()
        );
    }

    private RefundEligibilityModel manualReview(OrderSnapshotModel order, String message) {
        return new RefundEligibilityModel(
                RefundEligibilityEnum.MANUAL_REVIEW_REQUIRED,
                message,
                order.paidAmount(),
                order.currency() == null ? "" : order.currency()
        );
    }
}
