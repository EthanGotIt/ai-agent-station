package cn.ethan.core.commerce.order;

/**
 * 订单诊断类型枚举：描述确定性履约诊断的稳定结论。
 *
 * @author ethan
 * @date 2026-08-06
 */
public enum OrderDiagnosisTypeEnum {
    SHIPMENT_DELAY,
    DELIVERY_OVERDUE,
    LOGISTICS_STALLED,
    DELIVERY_DISPUTE,
    INSUFFICIENT_DATA,
    NO_ANOMALY
}
