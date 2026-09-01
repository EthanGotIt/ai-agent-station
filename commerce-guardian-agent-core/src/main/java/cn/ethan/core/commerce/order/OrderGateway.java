package cn.ethan.core.commerce.order;

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
     * 按结构化条件查询当前用户订单；默认实现让只支持单订单查询的旧适配器安全降级。
     */
    default OrderSearchResultModel searchOrders(OrderSearchCriteria criteria, String userId) {
        return OrderSearchResultModel.temporaryFailure();
    }

}
