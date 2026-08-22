package cn.ethan.core.commerce.order;

import java.time.Instant;

/**
 * 类型职责：定义订单外部写操作在订单能力边界内的结果，不承担 Workflow 状态或重试。
 *
 * @author ethan
 * @date 2026-08-22
 */
public interface OrderActionGateway {

    OrderActionResult refund(String userId, String orderId, String reason, Instant now);

    /** 提交催发货事实；外部订单系统不支持时必须返回受控失败，不得静默成功。 */
    default OrderActionResult expedite(String userId, String orderId, Instant now) {
        return OrderActionResult.failed(false, "ACTION_NOT_SUPPORTED", "当前订单服务不支持催发货");
    }

    /** 更新订单历史可见性；只改变用户历史视图，不删除订单或物流审计事实。 */
    default OrderActionResult setVisibility(
            String userId,
            String orderId,
            OrderVisibilityEnum visibility,
            Instant now
    ) {
        return OrderActionResult.failed(false, "ACTION_NOT_SUPPORTED", "当前订单服务不支持订单历史管理");
    }

    record OrderActionResult(boolean success, boolean retryable, String code, String message) {
        public OrderActionResult {
            code = code == null ? "" : code;
            message = message == null ? "" : message;
        }

        public static OrderActionResult succeeded(String code, String message) {
            return new OrderActionResult(true, false, code, message);
        }

        public static OrderActionResult failed(boolean retryable, String code, String message) {
            return new OrderActionResult(false, retryable, code, message);
        }
    }
}
