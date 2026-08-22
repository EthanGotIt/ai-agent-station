package cn.ethan.infrastructure.commerce.order.persistence;

import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderActionGateway;
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
public final class LocalOrderGateway implements OrderGateway, OrderActionGateway {

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

    @Override
    public OrderActionResult refund(String userId, String orderId, String reason, Instant now) {
        if (userId == null || userId.isBlank() || orderId == null || orderId.isBlank()
                || reason == null || reason.isBlank() || now == null) {
            return OrderActionResult.failed(false, "REFUND_ARGUMENT_INVALID", "退款参数不完整");
        }
        try {
            DemoOrderEntity current = mapper.selectById(orderId.strip());
            if (current == null) {
                return OrderActionResult.failed(false, "ORDER_NOT_FOUND", "订单不存在");
            }
            if (!userId.strip().equals(current.getUserId())) {
                return OrderActionResult.failed(false, "ORDER_NOT_OWNED", "订单不属于当前用户");
            }
            if (OrderStatusEnum.REFUNDED.name().equalsIgnoreCase(current.getStatus())) {
                return OrderActionResult.succeeded("ALREADY_REFUNDED", "订单已完成退款");
            }
            if (!List.of(OrderStatusEnum.PAID.name(), OrderStatusEnum.SHIPPED.name(),
                    OrderStatusEnum.DELIVERED.name()).contains(current.getStatus())) {
                return OrderActionResult.failed(false, "REFUND_ORDER_STATE_INVALID", "当前订单状态不允许退款");
            }
            int updated = mapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<DemoOrderEntity>()
                    .eq("ORDER_ID", orderId.strip())
                    .eq("USER_ID", userId.strip())
                    .in("STATUS", List.of(OrderStatusEnum.PAID.name(), OrderStatusEnum.SHIPPED.name(),
                            OrderStatusEnum.DELIVERED.name()))
                    .set("STATUS", OrderStatusEnum.REFUNDED.name())
                    .set("UPDATED_AT", now));
            if (updated == 1) {
                return OrderActionResult.succeeded("REFUNDED", "订单已完成退款");
            }
            DemoOrderEntity after = mapper.selectById(orderId.strip());
            if (after != null && OrderStatusEnum.REFUNDED.name().equalsIgnoreCase(after.getStatus())) {
                return OrderActionResult.succeeded("ALREADY_REFUNDED", "订单已完成退款");
            }
            return OrderActionResult.failed(true, "REFUND_STATE_RACE", "订单状态正在变化，请稍后重试");
        } catch (RuntimeException failure) {
            LOGGER.warn("本地退款更新暂时失败，exception={}", failure.getClass().getSimpleName());
            return OrderActionResult.failed(true, "ORDER_STORE_TEMPORARY_FAILURE", "订单状态暂时无法更新");
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
