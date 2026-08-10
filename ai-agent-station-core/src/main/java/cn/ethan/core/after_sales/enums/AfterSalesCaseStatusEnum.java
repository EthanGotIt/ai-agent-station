package cn.ethan.core.after_sales.enums;

/**
 * 售后申请状态枚举：人工审核属于外部边界，本项目负责创建和查询状态。
 *
 * @author ethan
 * @date 2026-08-10
 */
public enum AfterSalesCaseStatusEnum {
    PENDING_REVIEW,
    REFUND_PROCESSING,
    COMPLETED,
    REJECTED
}
