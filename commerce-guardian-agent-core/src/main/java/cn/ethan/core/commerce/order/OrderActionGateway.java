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
