package cn.ethan.core.order.enums;

/**
 * 履约问题类型枚举：用于诊断意图不完整时的确定性 QuestionCard。
 *
 * @author ethan
 * @date 2026-08-10
 */
public enum OrderIssueTypeEnum {
    NOT_SHIPPED,
    LOGISTICS_STALLED,
    DELIVERY_OVERDUE,
    DELIVERED_NOT_RECEIVED,
    OTHER
}
