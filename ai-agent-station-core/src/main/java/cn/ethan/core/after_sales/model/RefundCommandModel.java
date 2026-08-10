package cn.ethan.core.after_sales.model;

import cn.ethan.core.after_sales.enums.RefundReasonEnum;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退款命令模型：由确认后的确定性 Workflow 创建，使用运行实例作为幂等键。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record RefundCommandModel(
        String workflowRunId,
        String caseId,
        String orderId,
        String userId,
        RefundReasonEnum reason,
        BigDecimal amount,
        String currency,
        Instant createdAt
) {

    public RefundCommandModel {
        if (workflowRunId == null || workflowRunId.isBlank()
                || caseId == null || caseId.isBlank()
                || orderId == null || orderId.isBlank()
                || userId == null || userId.isBlank()
                || reason == null
                || amount == null || amount.signum() < 0
                || currency == null || currency.isBlank()
                || createdAt == null) {
            throw new IllegalArgumentException("refund command is incomplete");
        }
        currency = currency.strip().toUpperCase();
    }

    public RefundCommandModel(
            String workflowRunId,
            String orderId,
            String userId,
            RefundReasonEnum reason,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
        this(workflowRunId, workflowRunId, orderId, userId, reason, amount, currency, createdAt);
    }
}
