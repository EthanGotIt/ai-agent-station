package cn.ethan.core.commerce.order;

import java.time.Instant;

/**
 * 类型职责：定义订单外部写操作在订单能力边界内的结果，不承担 Workflow 状态或重试。
 *
 * @author ethan
 * @date 2026-08-22
 */
public interface OrderActionGateway {

    /** 使用外部命令幂等键提交退款；订单服务必须按该键去重。 */
    OrderActionResult refund(
            String userId,
            String orderId,
            String reason,
            String idempotencyKey,
            Instant now
    );

    /** 使用外部命令幂等键提交催发货；不支持时必须返回受控失败。 */
    default OrderActionResult expedite(
            String userId,
            String orderId,
            String idempotencyKey,
            Instant now
    ) {
        return OrderActionResult.failed(false, "ACTION_NOT_SUPPORTED", "当前订单服务不支持催发货");
    }

    /** 使用外部命令幂等键更新订单历史可见性；只改变用户历史视图。 */
    default OrderActionResult setVisibility(
            String userId,
            String orderId,
            OrderVisibilityEnum visibility,
            String idempotencyKey,
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
