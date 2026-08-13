package cn.ethan.core.after_sales.enums;

/**
 * 退款命令状态枚举：表达可持久化异步退款任务的领取、重试与终态。
 *
 * @author ethan
 * @date 2026-08-12
 */
public enum RefundCommandStatusEnum {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    COMPLETED,
    FAILED
}
