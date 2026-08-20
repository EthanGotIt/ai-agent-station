package cn.ethan.infrastructure.commerce.order.persistence;

import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.LogisticsGateway;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地物流网关：先验证订单归属，再返回时间正序的物流轨迹。
 *
 * @author ethan
 * @date 2026-08-10
 */
@Component
@ConditionalOnProperty(name = "ai-agent.order.gateway", havingValue = "local", matchIfMissing = true)
public final class LocalLogisticsGateway implements LogisticsGateway {

    private final LocalOrderGateway orders;
    private final DemoLogisticsEventMapper mapper;

    public LocalLogisticsGateway(LocalOrderGateway orders, DemoLogisticsEventMapper mapper) {
        this.orders = orders;
        this.mapper = mapper;
    }

    @Override
    public List<LogisticsEventModel> findTrace(String orderId, String userId) {
        if (orders.findOrder(orderId, userId).status() != cn.ethan.core.commerce.order.OrderLookupStatusEnum.FOUND) {
            return List.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<DemoLogisticsEventEntity>()
                        .eq(DemoLogisticsEventEntity::getOrderId, orderId)
                        .orderByAsc(DemoLogisticsEventEntity::getOccurredAt))
                .stream()
                .map(event -> new LogisticsEventModel(
                        event.getEventId(), event.getOrderId(), event.getStatus(), event.getLocation(),
                        event.getDescription(), event.getOccurredAt()
                ))
                .toList();
    }
}
