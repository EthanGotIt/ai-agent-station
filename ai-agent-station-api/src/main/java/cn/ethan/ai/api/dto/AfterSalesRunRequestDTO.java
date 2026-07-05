package cn.ethan.ai.api.dto;

/**
 * 启动售后 Agent 请求。
 */
public record AfterSalesRunRequestDTO(String userId,
                                      String sessionId,
                                      String message,
                                      String orderId,
                                      String refundReason) {
}
