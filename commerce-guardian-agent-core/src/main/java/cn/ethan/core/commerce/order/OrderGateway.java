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

}
