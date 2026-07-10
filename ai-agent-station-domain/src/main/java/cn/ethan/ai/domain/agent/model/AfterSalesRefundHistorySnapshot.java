package cn.ethan.ai.domain.agent.model;

/**
 * 退款历史证据的最小标准化视图，不包含金额和支付信息。
 */
public record AfterSalesRefundHistorySnapshot(String orderId,
                                              boolean activeRefund,
                                              int completedRefundCount,
                                              String latestRefundStatus) {
}
