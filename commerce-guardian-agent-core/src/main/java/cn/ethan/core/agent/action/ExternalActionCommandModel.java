package cn.ethan.core.agent.action;


import java.time.Instant;

/**
 * 类型职责：保存一次具有幂等键和租约的外部动作命令。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record ExternalActionCommandModel(
        String commandId,
        String runId,
        String threadId,
        String turnId,
        String userId,
        ExternalActionTypeEnum type,
        String idempotencyKey,
        String payloadJson,
        ExternalActionStatusEnum status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        String leaseOwner,
        Instant leaseUntil,
        String lastErrorCode,
        String lastErrorMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        long version,
        int retryCycleAttemptCount
) {

    public ExternalActionCommandModel {
        if (commandId == null || commandId.isBlank() || runId == null || runId.isBlank()
                || threadId == null || threadId.isBlank() || userId == null || userId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank() || type == null) {
            throw new IllegalArgumentException("ExternalActionCommand identity must not be blank");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("ExternalActionCommand timestamps must not be null");
        }
        payloadJson = payloadJson == null ? "{}" : payloadJson;
        leaseOwner = leaseOwner == null || leaseOwner.isBlank() ? null : leaseOwner;
        if (status == null || attemptCount < 0 || maxAttempts < 1 || version < 0
                || retryCycleAttemptCount < 0 || retryCycleAttemptCount > attemptCount) {
            throw new IllegalArgumentException("ExternalActionCommand counters and status must be valid");
        }
        switch (status) {
            case PENDING, RETRY_WAIT -> {
                require(nextAttemptAt != null, "待执行状态必须具有 nextAttemptAt");
                require(leaseOwner == null && leaseUntil == null && completedAt == null,
                        "待执行状态不能保留 Lease 或完成时间");
            }
            case PROCESSING -> {
                require(nextAttemptAt == null, "PROCESSING 不能具有 nextAttemptAt");
                require(leaseOwner != null && leaseUntil != null && completedAt == null,
                        "PROCESSING 必须具有完整 Lease 且不能完成");
            }
            case MANUAL_RETRY_REQUIRED -> {
                require(nextAttemptAt == null && leaseOwner == null && leaseUntil == null && completedAt == null,
                        "人工重试状态不能具有调度时间、Lease 或完成时间");
            }
            case SUCCEEDED -> {
                require(nextAttemptAt == null && leaseOwner == null && leaseUntil == null && completedAt != null,
                        "成功状态必须清理调度与 Lease 并具有完成时间");
            }
        }
    }

    /**
     * 兼容旧调用方的构造边界；旧数据的 ATTEMPT_COUNT 已按总尝试次数解释，周期次数从同值开始。
     */
    public ExternalActionCommandModel(
            String commandId,
            String runId,
            String threadId,
            String turnId,
            String userId,
            ExternalActionTypeEnum type,
            String idempotencyKey,
            String payloadJson,
            ExternalActionStatusEnum status,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            String leaseOwner,
            Instant leaseUntil,
            String lastErrorCode,
            String lastErrorMessage,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
        this(commandId, runId, threadId, turnId, userId, type, idempotencyKey, payloadJson, status,
                attemptCount, maxAttempts, nextAttemptAt, leaseOwner, leaseUntil, lastErrorCode,
                lastErrorMessage, createdAt, updatedAt, completedAt, 0L, attemptCount);
    }

    /** 总尝试次数；该值跨人工重试周期单调递增。 */
    public int totalAttemptCount() {
        return attemptCount;
    }

    /** 当前人工重试周期内已领取的尝试次数；人工重试时重置为零。 */
    public int currentRetryCycleAttemptCount() {
        return retryCycleAttemptCount;
    }

    public ExternalActionCommandModel claimed(String workerId, Instant leaseUntil, Instant now) {
        if (workerId == null || workerId.isBlank() || leaseUntil == null || now == null
                || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("领取命令必须提供有效 Worker、Lease 和时间");
        }
        if (status != ExternalActionStatusEnum.PENDING && status != ExternalActionStatusEnum.RETRY_WAIT
                && status != ExternalActionStatusEnum.PROCESSING) {
            throw new IllegalStateException("当前状态不能领取外部动作命令");
        }
        if (status == ExternalActionStatusEnum.PROCESSING && this.leaseUntil.isAfter(now)) {
            throw new IllegalStateException("未到期 Lease 不能被接管");
        }
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.PROCESSING, attemptCount + 1, maxAttempts,
                null, workerId, leaseUntil, lastErrorCode, lastErrorMessage, createdAt, now, completedAt,
                version + 1, retryCycleAttemptCount + 1);
    }

    public ExternalActionCommandModel succeeded(Instant now) {
        requireProcessing(now);
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.SUCCEEDED, attemptCount, maxAttempts,
                null, null, null, null, null, createdAt, now, now, version + 1, retryCycleAttemptCount);
    }

    public ExternalActionCommandModel retryAt(Instant next, String code, String message, Instant now) {
        requireProcessing(now);
        ExternalActionStatusEnum nextStatus = retryCycleAttemptCount >= maxAttempts
                ? ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED : ExternalActionStatusEnum.RETRY_WAIT;
        if (nextStatus == ExternalActionStatusEnum.RETRY_WAIT && next == null) {
            throw new IllegalArgumentException("RETRY_WAIT 必须具有 nextAttemptAt");
        }
        Instant effectiveNext = nextStatus == ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED ? null : next;
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, nextStatus, attemptCount, maxAttempts, effectiveNext, null, null, code, message,
                createdAt, now, null, version + 1, retryCycleAttemptCount);
    }

    public ExternalActionCommandModel failedPermanently(String code, String message, Instant now) {
        requireProcessing(now);
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED, attemptCount, maxAttempts,
                null, null, null, code, message, createdAt, now, null, version + 1, retryCycleAttemptCount);
    }

    /** 将最终失败命令重新放回队列，保留原命令和幂等键。 */
    public ExternalActionCommandModel manualRetry(Instant now) {
        if (status != ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED) {
            throw new IllegalStateException("只有人工重试终态允许重新入队");
        }
        if (now == null) {
            throw new IllegalArgumentException("人工重试时间不能为空");
        }
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.PENDING, attemptCount, maxAttempts, now,
                null, null, null, null, createdAt, now, null, version + 1, 0);
    }

    private void requireProcessing(Instant now) {
        if (status != ExternalActionStatusEnum.PROCESSING) {
            throw new IllegalStateException("完成或失败转换只允许从 PROCESSING 开始");
        }
        if (now == null) {
            throw new IllegalArgumentException("状态转换时间不能为空");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
