package cn.ethan.ai.domain.agent.model.plan;

/**
 * 退款信息收集检查项。
 *
 * @param item   字段名，如 orderId、refundReason
 * @param status 状态：PENDING 或 DONE
 */
public record ChecklistItem(
        String item,
        String status
) {
}
