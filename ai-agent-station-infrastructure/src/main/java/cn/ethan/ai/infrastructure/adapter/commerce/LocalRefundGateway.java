package cn.ethan.ai.infrastructure.adapter.commerce;

import cn.ethan.ai.domain.agent.port.driven.IRefundGateway;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.RefundGatewayResult;
import cn.ethan.ai.infrastructure.dao.DemoOrderMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.commerce-adapter", havingValue = "local", matchIfMissing = true)
public class LocalRefundGateway implements IRefundGateway {

    private final DemoOrderMapper orderMapper;
    private final LocalOrderGateway orderGateway;

    public LocalRefundGateway(DemoOrderMapper orderMapper, LocalOrderGateway orderGateway) {
        this.orderMapper = orderMapper;
        this.orderGateway = orderGateway;
    }

    @Override
    public RefundGatewayResult executeRefund(String orderId, String userId, String idempotencyKey) {
        int changed = orderMapper.updateStatusForRefund(orderId, userId);
        if (changed > 0) {
            return new RefundGatewayResult(true, false, "REFUND_EXECUTED");
        }
        Optional<AfterSalesOrderSnapshot> order = orderGateway.findOrder(orderId, userId);
        if (order.isPresent() && "REFUNDED".equalsIgnoreCase(order.get().status())) {
            return new RefundGatewayResult(true, true, "ALREADY_REFUNDED");
        }
        return new RefundGatewayResult(false, false, "ORDER_STATE_CONFLICT");
    }
}
