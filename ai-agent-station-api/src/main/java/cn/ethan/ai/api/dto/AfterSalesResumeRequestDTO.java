package cn.ethan.ai.api.dto;

/**
 * 恢复售后 Agent 请求（补充信息或审批）。
 */
public record AfterSalesResumeRequestDTO(String checkpointId,
                                         String action,
                                         String orderId,
                                         String refundReason) {
}
