package cn.ethan.ai.infrastructure.adapter.commerce;

import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.infrastructure.dao.DemoOrderMapper;
import cn.ethan.ai.infrastructure.dao.po.DemoOrderPO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.commerce-adapter", havingValue = "local", matchIfMissing = true)
public class LocalOrderGateway implements IOrderGateway {

    private final DemoOrderMapper orderMapper;

    public LocalOrderGateway(DemoOrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
        if (orderId == null || orderId.isBlank() || requesterId == null || requesterId.isBlank()) {
            return Optional.empty();
        }
        DemoOrderPO order = orderMapper.selectByOrderId(orderId);
        if (order == null) {
            return Optional.empty();
        }
        boolean owned = requesterId.equals(order.getUserId());
        return Optional.of(new AfterSalesOrderSnapshot(
                order.getOrderId(),
                owned ? requesterId : "__FOREIGN__",
                owned ? order.getStatus() : "ACCESS_DENIED",
                owned ? order.getDaysSinceDelivery() : null
        ));
    }
}
