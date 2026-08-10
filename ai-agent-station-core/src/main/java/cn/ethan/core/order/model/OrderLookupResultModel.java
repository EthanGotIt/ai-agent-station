package cn.ethan.core.order.model;

import cn.ethan.core.order.enums.OrderLookupStatusEnum;

/**
 * 订单查询结果模型：统一描述查询成功、无数据、无权限和临时故障。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record OrderLookupResultModel(OrderLookupStatusEnum status, OrderSnapshotModel order) {

    public OrderLookupResultModel {
        if (status == null) {
            throw new IllegalArgumentException("order lookup status is required");
        }
        if (status == OrderLookupStatusEnum.FOUND && order == null) {
            throw new IllegalArgumentException("found order lookup requires an order snapshot");
        }
    }

    public static OrderLookupResultModel found(OrderSnapshotModel order) {
        return new OrderLookupResultModel(OrderLookupStatusEnum.FOUND, order);
    }

    public static OrderLookupResultModel notFound() {
        return new OrderLookupResultModel(OrderLookupStatusEnum.NOT_FOUND, null);
    }

    public static OrderLookupResultModel denied() {
        return new OrderLookupResultModel(OrderLookupStatusEnum.ACCESS_DENIED, null);
    }

    public static OrderLookupResultModel temporaryFailure() {
        return new OrderLookupResultModel(OrderLookupStatusEnum.TEMPORARY_FAILURE, null);
    }
}
