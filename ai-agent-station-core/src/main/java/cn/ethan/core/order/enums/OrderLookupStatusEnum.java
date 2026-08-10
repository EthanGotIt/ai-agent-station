package cn.ethan.core.order.enums;

/**
 * 订单查询状态枚举：描述订单查询的确定性结果。
 *
 * @author ethan
 * @date 2026-08-05
 */
public enum OrderLookupStatusEnum {
    FOUND,
    NOT_FOUND,
    ACCESS_DENIED,
    TEMPORARY_FAILURE
}
