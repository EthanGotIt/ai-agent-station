package cn.ethan.dto;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 退款命令响应 DTO：公开任务状态和稳定错误码，不泄露渠道原始响应。
 *
 * @author ethan
 * @date 2026-08-12
 */
public record AgentAfterSalesRefundCommandDto(
        String refundId,
        String workflowRunId,
        String status,
        BigDecimal amount,
        String currency,
        String retryId,
        int attemptCount,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String failureCode,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public static AgentAfterSalesRefundCommandDto from(RefundCommandResultModel model) {
        return new AgentAfterSalesRefundCommandDto(
                model.refundId(), model.workflowRunId(), model.status(), model.amount(), model.currency(),
                model.retryId(), model.attemptCount(), model.nextAttemptAt(), model.leaseUntil(),
                model.failureCode(), model.version(), model.createdAt(), model.updatedAt()
        );
    }
}
