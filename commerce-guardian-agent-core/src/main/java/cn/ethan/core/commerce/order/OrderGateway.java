package cn.ethan.core.commerce.order;


import java.util.List;

/**
 * 订单网关：为订单 Workflow 提供只读查询端口，具体实现位于基础设施层。
 *
 * @author ethan
 * @date 2026-08-05
 */
@FunctionalInterface
public interface OrderGateway {

    OrderLookupResultModel findOrder(String orderId, String userId);

    /**
     * 读取当前用户近期订单，缺参卡只显示最小摘要，避免泄露订单明细。
     */
    default List<RecentOrderModel> listRecentOrders(String userId, int limit) {
        return List.of();
    }

    /**
     * 读取已完成归属校验的订单商品；不支持时返回空列表而不改变查询主链路。
     */
    default List<OrderItemModel> findItems(String orderId, String userId) {
        return List.of();
    }
}
