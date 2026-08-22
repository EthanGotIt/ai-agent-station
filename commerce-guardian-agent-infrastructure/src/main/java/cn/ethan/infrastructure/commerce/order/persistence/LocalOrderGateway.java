package cn.ethan.infrastructure.commerce.order.persistence;

import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    public LocalOrderGateway(DemoOrderMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    @Autowired
    public LocalOrderGateway(DemoOrderMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
                    order.getCurrency(),
                    order.getItemSummary(),
                    order.getHiddenAt()
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
    public OrderSearchResultModel searchOrders(OrderSearchCriteria criteria, String userId) {
        if (criteria == null || userId == null || userId.isBlank()) {
            return OrderSearchResultModel.success(List.of());
        }
        try {
            LambdaQueryWrapper<DemoOrderEntity> query = new LambdaQueryWrapper<DemoOrderEntity>()
                    .eq(DemoOrderEntity::getUserId, userId.strip());
            if (criteria.visibility() == OrderVisibilityEnum.ACTIVE) {
                query.isNull(DemoOrderEntity::getHiddenAt);
            } else if (criteria.visibility() == OrderVisibilityEnum.HIDDEN) {
                query.isNotNull(DemoOrderEntity::getHiddenAt);
            }
            if (criteria.createdFrom() != null) {
                query.ge(DemoOrderEntity::getCreatedAt, criteria.createdFrom());
            }
            if (criteria.createdTo() != null) {
                query.le(DemoOrderEntity::getCreatedAt, criteria.createdTo());
            }
            if (criteria.minAmount() != null) {
                query.ge(DemoOrderEntity::getPaidAmount, criteria.minAmount());
            }
            if (criteria.maxAmount() != null) {
                query.le(DemoOrderEntity::getPaidAmount, criteria.maxAmount());
            }
            if (!criteria.statuses().isEmpty()) {
                query.in(DemoOrderEntity::getStatus,
                        criteria.statusList().stream().map(Enum::name).toList());
            }
            if (criteria.keyword() != null) {
                query.and(wrapper -> wrapper.like(DemoOrderEntity::getOrderId, criteria.keyword())
                        .or().like(DemoOrderEntity::getItemSummary, criteria.keyword())
                        .or().like(DemoOrderEntity::getLogisticsStatus, criteria.keyword()));
            }
            if (criteria.logisticsStalledDays() != null) {
                Instant cutoff = clock.instant().minusSeconds(criteria.logisticsStalledDays() * 86_400L);
                query.and(wrapper -> wrapper.isNull(DemoOrderEntity::getLastLogisticsAt)
                        .or().le(DemoOrderEntity::getLastLogisticsAt, cutoff));
            }
            query.orderByDesc(DemoOrderEntity::getCreatedAt);
            List<OrderSnapshotModel> orders = mapper.selectList(query).stream()
                    .limit(criteria.limit())
                    .map(this::toSnapshot)
                    .toList();
            return new OrderSearchResultModel(OrderSearchStatusEnum.SUCCESS, orders);
        } catch (RuntimeException failure) {
            LOGGER.warn("本地订单搜索降级为临时失败，exception={}", failure.getClass().getSimpleName());
            return OrderSearchResultModel.temporaryFailure();
        }
    }

    private OrderSnapshotModel toSnapshot(DemoOrderEntity order) {
        return new OrderSnapshotModel(
                order.getOrderId(), order.getUserId(), OrderStatusEnum.fromValue(order.getStatus()),
                order.getDaysSinceDelivery(), order.getCreatedAt(), order.getExpectedDeliveryAt(),
                order.getLastLogisticsAt(), order.getLogisticsStatus(), order.getPaidAmount(),
                order.getCurrency(), order.getItemSummary(), order.getHiddenAt());
    }

}
