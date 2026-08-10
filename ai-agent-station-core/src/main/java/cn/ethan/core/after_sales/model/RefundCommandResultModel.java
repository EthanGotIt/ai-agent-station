package cn.ethan.core.after_sales.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退款命令结果模型：提供幂等创建后的稳定退款单状态。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record RefundCommandResultModel(
        String refundId,
        String caseId,
        String orderId,
        String userId,
        String status,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {

    public RefundCommandResultModel {
        if (refundId == null || refundId.isBlank()
                || caseId == null || caseId.isBlank()
                || orderId == null || orderId.isBlank()
                || userId == null || userId.isBlank()
                || status == null || status.isBlank()
                || amount == null || amount.signum() < 0
                || currency == null || currency.isBlank()
                || createdAt == null) {
            throw new IllegalArgumentException("refund command result is incomplete");
        }
        currency = currency.strip().toUpperCase();
    }

    public RefundCommandResultModel(
            String refundId,
            String orderId,
            String userId,
            String status,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
        this(refundId, refundId, orderId, userId, status, amount, currency, createdAt);
    }
}
