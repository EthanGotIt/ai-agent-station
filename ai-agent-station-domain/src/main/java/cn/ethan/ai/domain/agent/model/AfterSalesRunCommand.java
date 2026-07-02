package cn.ethan.ai.domain.agent.model;

public record AfterSalesRunCommand(String userId,
                                   String sessionId,
                                   String message,
                                   String orderId,
                                   String refundReason) {
}
