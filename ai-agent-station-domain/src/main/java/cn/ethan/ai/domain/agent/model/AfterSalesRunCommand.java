package cn.ethan.ai.domain.agent.model;

import cn.ethan.ai.types.common.id.OrderId;
import cn.ethan.ai.types.common.id.SessionId;
import cn.ethan.ai.types.common.id.UserId;

/**
 * 售后Agent启动命令。
 */
public record AfterSalesRunCommand(UserId userId,
                                   SessionId sessionId,
                                   String message,
                                   OrderId orderId,
                                   String refundReason) {

    public String userIdValue() {
        return userId == null ? null : userId.value();
    }

    public String sessionIdValue() {
        return sessionId == null ? null : sessionId.value();
    }

    public String orderIdValue() {
        return orderId == null ? null : orderId.value();
    }

    public static AfterSalesRunCommand of(String userId,
                                          String sessionId,
                                          String message,
                                          String orderId,
                                          String refundReason) {
        return new AfterSalesRunCommand(
                UserId.of(userId),
                SessionId.of(sessionId),
                message,
                orderId == null || orderId.isBlank() ? null : OrderId.of(orderId),
                refundReason
        );
    }
}
