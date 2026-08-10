package cn.ethan.core.after_sales.enums;

/**
 * 退款资格枚举：区分可直接确认、需人工处理和不可退款结果。
 *
 * @author ethan
 * @date 2026-08-07
 */
public enum RefundEligibilityEnum {
    APPROVED,
    MANUAL_REVIEW_REQUIRED,
    REJECTED
}
