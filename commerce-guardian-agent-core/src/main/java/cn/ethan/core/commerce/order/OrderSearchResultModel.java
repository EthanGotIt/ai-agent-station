package cn.ethan.core.commerce.order;

import java.util.List;

/**
 * 订单搜索结果：返回经过用户归属过滤的有限订单快照集合。
 *
 * @author ethan
 * @date 2026-08-22
 */
public record OrderSearchResultModel(
        OrderSearchStatusEnum status,
        List<OrderSnapshotModel> orders
) {

    public OrderSearchResultModel {
        if (status == null) {
            throw new IllegalArgumentException("订单搜索状态不能为空");
        }
        orders = orders == null ? List.of() : List.copyOf(orders);
        if (status == OrderSearchStatusEnum.TEMPORARY_FAILURE && !orders.isEmpty()) {
            throw new IllegalArgumentException("临时失败结果不能携带订单事实");
        }
    }

    public static OrderSearchResultModel success(List<OrderSnapshotModel> orders) {
        return new OrderSearchResultModel(OrderSearchStatusEnum.SUCCESS, orders);
    }

    public static OrderSearchResultModel temporaryFailure() {
        return new OrderSearchResultModel(OrderSearchStatusEnum.TEMPORARY_FAILURE, List.of());
    }
}
