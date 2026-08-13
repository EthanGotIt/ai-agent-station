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
        String operatorId,
        String decisionId,
        String decisionNote,
        Instant reviewedAt,
        String failureCode,
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
        operatorId = operatorId == null ? "" : operatorId.strip();
        decisionId = decisionId == null ? "" : decisionId.strip();
        decisionNote = decisionNote == null ? "" : decisionNote.strip();
        failureCode = failureCode == null ? "" : failureCode.strip();
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("after-sales case amount is invalid");
        }
        if (!decisionId.isBlank() && (operatorId.isBlank() || reviewedAt == null)) {
            throw new IllegalArgumentException("after-sales review metadata is incomplete");
        }
    }

    public AfterSalesCaseModel(
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
        this(caseId, workflowRunId, userId, orderId, reason, description, handlingMode, status,
                amount, currency, refundId, "", "", "", null, "", version, createdAt, updatedAt);
    }

    public AfterSalesCaseModel withRefund(String nextRefundId, Instant now) {
        return new AfterSalesCaseModel(
                caseId, workflowRunId, userId, orderId, reason, description, handlingMode,
                AfterSalesCaseStatusEnum.REFUND_PROCESSING, amount, currency, nextRefundId,
                operatorId, decisionId, decisionNote, reviewedAt, "", version + 1, createdAt, now
        );
    }

    public AfterSalesCaseModel reviewed(
            AfterSalesCaseStatusEnum nextStatus,
            String nextOperatorId,
            String nextDecisionId,
            String nextDecisionNote,
            String nextRefundId,
            Instant now
    ) {
        if (status != AfterSalesCaseStatusEnum.PENDING_REVIEW
                || (nextStatus != AfterSalesCaseStatusEnum.REFUND_PROCESSING
                && nextStatus != AfterSalesCaseStatusEnum.REJECTED)) {
            throw new IllegalStateException("after-sales review transition is invalid");
        }
        return new AfterSalesCaseModel(
                caseId, workflowRunId, userId, orderId, reason, description, handlingMode, nextStatus,
                amount, currency, nextRefundId, nextOperatorId, nextDecisionId, nextDecisionNote,
                now, "", version + 1, createdAt, now
        );
    }

    public AfterSalesCaseModel withCompleted(Instant now) {
        if (status != AfterSalesCaseStatusEnum.REFUND_PROCESSING) {
            throw new IllegalStateException("after-sales completion transition is invalid");
        }
        return new AfterSalesCaseModel(
                caseId, workflowRunId, userId, orderId, reason, description, handlingMode,
                AfterSalesCaseStatusEnum.COMPLETED, amount, currency, refundId,
                operatorId, decisionId, decisionNote, reviewedAt, "", version + 1, createdAt, now
        );
    }

    public AfterSalesCaseModel withRefundFailure(String nextFailureCode, Instant now) {
        if (status != AfterSalesCaseStatusEnum.REFUND_PROCESSING) {
            throw new IllegalStateException("after-sales failure transition is invalid");
        }
        return new AfterSalesCaseModel(
                caseId, workflowRunId, userId, orderId, reason, description, handlingMode,
                AfterSalesCaseStatusEnum.REFUND_FAILED, amount, currency, refundId,
                operatorId, decisionId, decisionNote, reviewedAt, nextFailureCode,
                version + 1, createdAt, now
        );
    }

    public AfterSalesCaseModel requeued(Instant now) {
        if (status != AfterSalesCaseStatusEnum.REFUND_FAILED) {
            throw new IllegalStateException("after-sales retry transition is invalid");
        }
        return new AfterSalesCaseModel(
                caseId, workflowRunId, userId, orderId, reason, description, handlingMode,
                AfterSalesCaseStatusEnum.REFUND_PROCESSING, amount, currency, refundId,
                operatorId, decisionId, decisionNote, reviewedAt, "", version + 1, createdAt, now
        );
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
