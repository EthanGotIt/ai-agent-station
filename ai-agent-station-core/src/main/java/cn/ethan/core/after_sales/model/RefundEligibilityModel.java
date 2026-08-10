package cn.ethan.core.after_sales.model;

import cn.ethan.core.after_sales.enums.RefundEligibilityEnum;

import java.math.BigDecimal;

/**
 * 退款资格模型：封装规则计算后的决定、金额和面向用户的说明。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record RefundEligibilityModel(
        RefundEligibilityEnum decision,
        String message,
        BigDecimal refundAmount,
        String currency
) {

    public RefundEligibilityModel {
        if (decision == null || message == null || message.isBlank()) {
            throw new IllegalArgumentException("refund eligibility is incomplete");
        }
        if (refundAmount != null && refundAmount.signum() < 0) {
            throw new IllegalArgumentException("refundAmount must not be negative");
        }
        currency = currency == null ? "" : currency.strip().toUpperCase();
    }
}
