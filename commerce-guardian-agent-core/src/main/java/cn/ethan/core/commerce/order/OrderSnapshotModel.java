package cn.ethan.core.commerce.order;


import java.time.Instant;
import java.math.BigDecimal;

/**
 * 订单快照模型：用于生成确定性回复的只读订单数据。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record OrderSnapshotModel(
        String orderId,
        String userId,
        OrderStatusEnum status,
        Integer daysSinceDelivery,
        Instant createdAt,
        Instant expectedDeliveryAt,
        Instant lastLogisticsAt,
        String logisticsStatus,
        BigDecimal paidAmount,
        String currency
) {

    public OrderSnapshotModel {
        if (orderId == null || orderId.isBlank()
                || userId == null || userId.isBlank()
                || status == null) {
            throw new IllegalArgumentException("order snapshot is incomplete");
        }
        logisticsStatus = logisticsStatus == null || logisticsStatus.isBlank()
                ? null
                : logisticsStatus.strip();
        if (paidAmount != null && paidAmount.signum() < 0) {
            throw new IllegalArgumentException("paidAmount must not be negative");
        }
        currency = currency == null || currency.isBlank() ? null : currency.strip().toUpperCase();
    }

    public OrderSnapshotModel(
            String orderId,
            String userId,
            OrderStatusEnum status,
            Integer daysSinceDelivery,
            Instant createdAt,
            Instant expectedDeliveryAt,
            Instant lastLogisticsAt,
            String logisticsStatus
    ) {
        this(
                orderId,
                userId,
                status,
                daysSinceDelivery,
                createdAt,
                expectedDeliveryAt,
                lastLogisticsAt,
                logisticsStatus,
                null,
                null
        );
    }

    public OrderSnapshotModel(
            String orderId,
            String userId,
            String status,
            Integer daysSinceDelivery
    ) {
        this(
                orderId,
                userId,
                OrderStatusEnum.fromValue(status),
                daysSinceDelivery,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
