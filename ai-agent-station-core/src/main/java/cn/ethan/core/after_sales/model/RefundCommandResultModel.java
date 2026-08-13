package cn.ethan.core.after_sales.model;

import java.math.BigDecimal;
import java.time.Instant;

import cn.ethan.core.after_sales.enums.RefundCommandStatusEnum;

/**
 * 退款命令结果模型：提供幂等创建后的稳定退款单状态。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record RefundCommandResultModel(
        String refundId,
        String caseId,
        String workflowRunId,
        String orderId,
        String userId,
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

    public RefundCommandResultModel {
        if (refundId == null || refundId.isBlank()
                || caseId == null || caseId.isBlank()
                || workflowRunId == null || workflowRunId.isBlank()
                || orderId == null || orderId.isBlank()
                || userId == null || userId.isBlank()
                || status == null || status.isBlank()
                || amount == null || amount.signum() < 0
                || currency == null || currency.isBlank()
                || attemptCount < 0 || version < 0 || nextAttemptAt == null
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("refund command result is incomplete");
        }
        try {
            RefundCommandStatusEnum.valueOf(status);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("refund command status is invalid", invalid);
        }
        currency = currency.strip().toUpperCase();
        retryId = retryId == null ? "" : retryId.strip();
        failureCode = failureCode == null ? "" : failureCode.strip();
    }

    public RefundCommandResultModel(
            String refundId,
            String caseId,
            String orderId,
            String userId,
            String status,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
        this(refundId, caseId, caseId, orderId, userId, normalizeLegacyStatus(status), amount, currency,
                "", 0, createdAt, null, "", 0, createdAt, createdAt);
    }

    public RefundCommandResultModel(
            String refundId,
            String orderId,
            String userId,
            String status,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
        this(refundId, refundId, refundId, orderId, userId, normalizeLegacyStatus(status), amount, currency,
                "", 0, createdAt, null, "", 0, createdAt, createdAt);
    }

    public RefundCommandStatusEnum statusEnum() {
        return RefundCommandStatusEnum.valueOf(status);
    }

    public RefundCommandResultModel claimed(Instant nextLeaseUntil, Instant now) {
        if (statusEnum() != RefundCommandStatusEnum.PENDING
                && statusEnum() != RefundCommandStatusEnum.RETRY_WAIT
                && !(statusEnum() == RefundCommandStatusEnum.PROCESSING
                && leaseUntil != null && !leaseUntil.isAfter(now))) {
            throw new IllegalStateException("refund command cannot be claimed");
        }
        return next(RefundCommandStatusEnum.PROCESSING, retryId, attemptCount + 1,
                now, nextLeaseUntil, "", now);
    }

    public RefundCommandResultModel completed(Instant now) {
        requireProcessing();
        return next(RefundCommandStatusEnum.COMPLETED, retryId, attemptCount, now, null, "", now);
    }

    public RefundCommandResultModel retryWaiting(Instant retryAt, String nextFailureCode, Instant now) {
        requireProcessing();
        return next(RefundCommandStatusEnum.RETRY_WAIT, retryId, attemptCount,
                retryAt, null, nextFailureCode, now);
    }

    public RefundCommandResultModel failed(String nextFailureCode, Instant now) {
        requireProcessing();
        return next(RefundCommandStatusEnum.FAILED, retryId, attemptCount, now, null, nextFailureCode, now);
    }

    public RefundCommandResultModel requeued(String nextRetryId, Instant now) {
        if (statusEnum() != RefundCommandStatusEnum.FAILED) {
            throw new IllegalStateException("refund command cannot be manually retried");
        }
        return next(RefundCommandStatusEnum.PENDING, nextRetryId, 0, now, null, "", now);
    }

    private RefundCommandResultModel next(
            RefundCommandStatusEnum nextStatus,
            String nextRetryId,
            int nextAttemptCount,
            Instant nextAttemptAt,
            Instant nextLeaseUntil,
            String nextFailureCode,
            Instant now
    ) {
        return new RefundCommandResultModel(
                refundId, caseId, workflowRunId, orderId, userId, nextStatus.name(), amount, currency,
                nextRetryId, nextAttemptCount, nextAttemptAt, nextLeaseUntil, nextFailureCode,
                version + 1, createdAt, now
        );
    }

    private void requireProcessing() {
        if (statusEnum() != RefundCommandStatusEnum.PROCESSING) {
            throw new IllegalStateException("refund command is not processing");
        }
    }

    private static String normalizeLegacyStatus(String status) {
        return "ACCEPTED".equals(status) ? RefundCommandStatusEnum.PENDING.name() : status;
    }
}
