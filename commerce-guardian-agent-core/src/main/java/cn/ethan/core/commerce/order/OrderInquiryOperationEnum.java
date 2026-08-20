package cn.ethan.core.commerce.order;

/**
 * 订单查询操作枚举：区分状态查询和履约异常诊断两条固定分支。
 *
 * @author ethan
 * @date 2026-08-07
 */
public enum OrderInquiryOperationEnum {
    QUERY,
    TRACK,
    DIAGNOSE
}
