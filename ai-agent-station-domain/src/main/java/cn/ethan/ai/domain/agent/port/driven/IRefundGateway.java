package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.RefundGatewayResult;

public interface IRefundGateway {

    RefundGatewayResult executeRefund(String orderId, String userId, String idempotencyKey);
}
