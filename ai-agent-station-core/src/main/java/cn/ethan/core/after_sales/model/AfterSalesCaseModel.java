package cn.ethan.core.after_sales.model;

import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 售后申请模型：作为退款 Workflow 的业务事实，不以 WorkflowRun 代替申请单。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AfterSalesCaseModel(
        String caseId,
        String workflowRunId,
        String userId,
        String orderId,
        RefundReasonEnum reason,
        String description,
        AfterSalesHandlingModeEnum handlingMode,
        AfterSalesCaseStatusEnum status,
        BigDecimal amount,
        String currency,
        String refundId,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    public AfterSalesCaseModel {
        if (blank(caseId) || blank(workflowRunId) || blank(userId) || blank(orderId)
                || reason == null || handlingMode == null || status == null || version < 0
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("after-sales case is incomplete");
        }
        description = description == null ? "" : description.strip();
        currency = currency == null ? "" : currency.strip().toUpperCase(java.util.Locale.ROOT);
        refundId = refundId == null ? "" : refundId.strip();
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("after-sales case amount is invalid");
        }
    }

    public AfterSalesCaseModel withRefund(String nextRefundId, Instant now) {
        return new AfterSalesCaseModel(
                caseId, workflowRunId, userId, orderId, reason, description, handlingMode,
                AfterSalesCaseStatusEnum.REFUND_PROCESSING, amount, currency, nextRefundId,
                version + 1, createdAt, now
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
