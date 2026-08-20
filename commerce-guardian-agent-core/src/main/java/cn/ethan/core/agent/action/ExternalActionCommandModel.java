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
        Instant completedAt
) {

    public ExternalActionCommandModel {
        if (commandId == null || commandId.isBlank() || runId == null || runId.isBlank()
                || threadId == null || threadId.isBlank() || userId == null || userId.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank() || type == null) {
            throw new IllegalArgumentException("ExternalActionCommand identity must not be blank");
        }
        payloadJson = payloadJson == null ? "{}" : payloadJson;
        status = status == null ? ExternalActionStatusEnum.PENDING : status;
        attemptCount = Math.max(0, attemptCount);
        maxAttempts = Math.max(1, maxAttempts);
    }

    public ExternalActionCommandModel claimed(String workerId, Instant leaseUntil, Instant now) {
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.PROCESSING, attemptCount + 1, maxAttempts,
                nextAttemptAt, workerId, leaseUntil, lastErrorCode, lastErrorMessage, createdAt, now, completedAt);
    }

    public ExternalActionCommandModel succeeded(Instant now) {
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.SUCCEEDED, attemptCount, maxAttempts,
                null, null, null, null, null, createdAt, now, now);
    }

    public ExternalActionCommandModel retryAt(Instant next, String code, String message, Instant now) {
        ExternalActionStatusEnum nextStatus = attemptCount >= maxAttempts
                ? ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED : ExternalActionStatusEnum.RETRY_WAIT;
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, nextStatus, attemptCount, maxAttempts, next, null, null, code, message,
                createdAt, now, null);
    }

    public ExternalActionCommandModel failedPermanently(String code, String message, Instant now) {
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED, attemptCount, maxAttempts,
                null, null, null, code, message, createdAt, now, null);
    }

    /** 将最终失败命令重新放回队列，保留原命令和幂等键。 */
    public ExternalActionCommandModel manualRetry(Instant now) {
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type, idempotencyKey,
                payloadJson, ExternalActionStatusEnum.PENDING, attemptCount, maxAttempts, now,
                null, null, null, null, createdAt, now, null);
    }
}
