package cn.ethan.infrastructure.order.gateway;

import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.model.OrderItemModel;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.port.OrderGateway;
import cn.ethan.infrastructure.order.entity.DemoOrderEntity;
import cn.ethan.infrastructure.order.mapper.DemoOrderMapper;
import cn.ethan.infrastructure.order.entity.DemoOrderItemEntity;
import cn.ethan.infrastructure.order.mapper.DemoOrderItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 本地订单网关：通过 MyBatis-Plus 访问演示订单数据。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Component
@ConditionalOnProperty(
        name = "ai-agent.order.gateway",
        havingValue = "local",
        matchIfMissing = true
)
public final class LocalOrderGateway implements OrderGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalOrderGateway.class);

    private final DemoOrderMapper mapper;
    private final DemoOrderItemMapper items;

    public LocalOrderGateway(DemoOrderMapper mapper, DemoOrderItemMapper items) {
        this.mapper = mapper;
        this.items = items;
    }

    @Override
    public OrderLookupResultModel findOrder(String orderId, String userId) {
        if (orderId == null || orderId.isBlank() || userId == null || userId.isBlank()) {
            return OrderLookupResultModel.notFound();
        }

        try {
            DemoOrderEntity order = mapper.selectById(orderId);
            if (order == null) {
                return OrderLookupResultModel.notFound();
            }
            if (!userId.equals(order.getUserId())) {
                return OrderLookupResultModel.denied();
            }
            return OrderLookupResultModel.found(new OrderSnapshotModel(
                    order.getOrderId(),
                    order.getUserId(),
                    OrderStatusEnum.fromValue(order.getStatus()),
                    order.getDaysSinceDelivery(),
                    order.getCreatedAt(),
                    order.getExpectedDeliveryAt(),
                    order.getLastLogisticsAt(),
                    order.getLogisticsStatus(),
                    order.getPaidAmount(),
                    order.getCurrency()
            ));
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "本地订单查询降级为临时失败，exception={}",
                    failure.getClass().getSimpleName()
            );
            return OrderLookupResultModel.temporaryFailure();
        }
    }

    @Override
    public List<RecentOrderModel> listRecentOrders(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), 10);
        return mapper.selectList(new LambdaQueryWrapper<DemoOrderEntity>()
                        .eq(DemoOrderEntity::getUserId, userId)
                        .orderByDesc(DemoOrderEntity::getCreatedAt)
                        .last("LIMIT " + effectiveLimit))
                .stream()
                .map(order -> new RecentOrderModel(
                        order.getOrderId(), OrderStatusEnum.fromValue(order.getStatus()), order.getCreatedAt()
                ))
                .toList();
    }

    @Override
    public List<OrderItemModel> findItems(String orderId, String userId) {
        if (findOrder(orderId, userId).status() != cn.ethan.core.order.enums.OrderLookupStatusEnum.FOUND) {
            return List.of();
        }
        return items.selectList(new LambdaQueryWrapper<DemoOrderItemEntity>()
                        .eq(DemoOrderItemEntity::getOrderId, orderId)
                        .orderByAsc(DemoOrderItemEntity::getItemId))
                .stream()
                .map(item -> new OrderItemModel(
                        item.getItemId(), item.getOrderId(), item.getProductName(), item.getQuantity(),
                        item.getUnitPrice()
                ))
                .toList();
    }
}
