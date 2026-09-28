package cn.ethan.core.agent.action;

import java.time.Instant;

/**
 * 类型职责：保存一次具有幂等键、租约和独立结果核验预算的外部动作命令。
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
        int retryCycleAttemptCount,
        ExternalActionOutcomeEnum outcome,
        int verificationAttemptCount,
        int maxVerificationAttempts
) {

    private static final int DEFAULT_MAX_VERIFICATION_ATTEMPTS = 3;

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
        if (status == null || outcome == null || attemptCount < 0 || maxAttempts < 1 || version < 0
                || retryCycleAttemptCount < 0 || retryCycleAttemptCount > attemptCount
                || verificationAttemptCount < 0 || maxVerificationAttempts < 1
                || verificationAttemptCount > maxVerificationAttempts) {
            throw new IllegalArgumentException("ExternalActionCommand counters and status must be valid: status="
                    + status + ", outcome=" + outcome + ", attempts=" + attemptCount + "/" + maxAttempts
                    + ", retryCycle=" + retryCycleAttemptCount + ", verification=" + verificationAttemptCount
                    + "/" + maxVerificationAttempts + ", version=" + version);
        }
        switch (status) {
            case PENDING, RETRY_WAIT -> {
                require(nextAttemptAt != null, "待执行状态必须具有 nextAttemptAt");
                require(leaseOwner == null && leaseUntil == null && completedAt == null,
                        "待执行状态不能保留 Lease 或完成时间");
            }
            case VERIFY_WAIT -> {
                require(outcome == ExternalActionOutcomeEnum.UNKNOWN && nextAttemptAt != null,
                        "待核验状态必须保留未知结果和调度时间");
                require(leaseOwner == null && leaseUntil == null && completedAt == null,
                        "待核验状态不能保留 Lease 或完成时间");
            }
            case PROCESSING -> {
                require(nextAttemptAt == null, "PROCESSING 不能具有 nextAttemptAt");
                require(leaseOwner != null && leaseUntil != null && completedAt == null,
                        "PROCESSING 必须具有完整 Lease 且不能完成");
            }
            case MANUAL_RETRY_REQUIRED -> {
                require(outcome == ExternalActionOutcomeEnum.FAILED,
                        "人工重试状态必须是已知失败");
                require(nextAttemptAt == null && leaseOwner == null && leaseUntil == null && completedAt == null,
                        "人工重试状态不能具有调度时间、Lease 或完成时间");
            }
            case MANUAL_VERIFICATION_REQUIRED -> {
                require(outcome == ExternalActionOutcomeEnum.UNKNOWN,
                        "人工核验状态必须保留未知结果");
                require(nextAttemptAt == null && leaseOwner == null && leaseUntil == null && completedAt == null,
                        "人工核验状态不能具有调度时间、Lease 或完成时间");
            }
            case SUCCEEDED -> {
                require(outcome == ExternalActionOutcomeEnum.SUCCEEDED,
                        "成功状态必须具有已核实成功结果");
                require(nextAttemptAt == null && leaseOwner == null && leaseUntil == null && completedAt != null,
                        "成功状态必须清理调度与 Lease 并具有完成时间");
            }
        }
        if (outcome == ExternalActionOutcomeEnum.UNKNOWN) {
            require(status == ExternalActionStatusEnum.PROCESSING
                            || status == ExternalActionStatusEnum.VERIFY_WAIT
                            || status == ExternalActionStatusEnum.MANUAL_VERIFICATION_REQUIRED,
                    "未知结果只能处于核验或核验执行状态");
        }
    }

    /** 兼容旧调用方及数据库记录的构造边界。 */
    public ExternalActionCommandModel(
            String commandId, String runId, String threadId, String turnId, String userId,
            ExternalActionTypeEnum type, String idempotencyKey, String payloadJson,
            ExternalActionStatusEnum status, int attemptCount, int maxAttempts, Instant nextAttemptAt,
            String leaseOwner, Instant leaseUntil, String lastErrorCode, String lastErrorMessage,
            Instant createdAt, Instant updatedAt, Instant completedAt, long version,
            int retryCycleAttemptCount
    ) {
        this(commandId, runId, threadId, turnId, userId, type, idempotencyKey, payloadJson, status,
                attemptCount, maxAttempts, nextAttemptAt, leaseOwner, leaseUntil, lastErrorCode, lastErrorMessage,
                createdAt, updatedAt, completedAt, version, retryCycleAttemptCount,
                legacyOutcome(status), 0, DEFAULT_MAX_VERIFICATION_ATTEMPTS);
    }

    /** 兼容旧调用方；旧数据的 ATTEMPT_COUNT 按总尝试次数解释。 */
    public ExternalActionCommandModel(
            String commandId, String runId, String threadId, String turnId, String userId,
            ExternalActionTypeEnum type, String idempotencyKey, String payloadJson,
            ExternalActionStatusEnum status, int attemptCount, int maxAttempts, Instant nextAttemptAt,
            String leaseOwner, Instant leaseUntil, String lastErrorCode, String lastErrorMessage,
            Instant createdAt, Instant updatedAt, Instant completedAt
    ) {
        this(commandId, runId, threadId, turnId, userId, type, idempotencyKey, payloadJson, status,
                attemptCount, maxAttempts, nextAttemptAt, leaseOwner, leaseUntil, lastErrorCode,
                lastErrorMessage, createdAt, updatedAt, completedAt, 0L, attemptCount);
    }

    /** 总尝试次数；该值跨人工重试周期单调递增。 */
    public int totalAttemptCount() {
        return attemptCount;
    }

    public ExternalActionCommandModel claimed(String workerId, Instant leaseUntil, Instant now) {
        if (workerId == null || workerId.isBlank() || leaseUntil == null || now == null
                || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("领取命令必须提供有效 Worker、Lease 和时间");
        }
        if (status != ExternalActionStatusEnum.PENDING && status != ExternalActionStatusEnum.RETRY_WAIT
                && status != ExternalActionStatusEnum.VERIFY_WAIT && status != ExternalActionStatusEnum.PROCESSING) {
            throw new IllegalStateException("当前状态不能领取外部动作命令");
        }
        if (status == ExternalActionStatusEnum.PROCESSING && this.leaseUntil.isAfter(now)) {
            throw new IllegalStateException("未到期 Lease 不能被接管");
        }
        int nextCycleCount = outcome == ExternalActionOutcomeEnum.UNKNOWN
                ? retryCycleAttemptCount : retryCycleAttemptCount + 1;
        return copy(ExternalActionStatusEnum.PROCESSING, attemptCount + 1, nextCycleCount, outcome,
                verificationAttemptCount, null, workerId, leaseUntil, lastErrorCode, lastErrorMessage,
                now, null, version + 1);
    }

    public ExternalActionCommandModel succeeded(Instant now) {
        requireProcessing(now);
        return copy(ExternalActionStatusEnum.SUCCEEDED, attemptCount, retryCycleAttemptCount,
                ExternalActionOutcomeEnum.SUCCEEDED, verificationAttemptCount, null, null, null,
                null, null, now, now, version + 1);
    }

    /** 明确失败且可重试时使用常规业务重试预算。 */
    public ExternalActionCommandModel retryAt(Instant next, String code, String message, Instant now) {
        requireProcessing(now);
        ExternalActionStatusEnum nextStatus = retryCycleAttemptCount >= maxAttempts
                ? ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED : ExternalActionStatusEnum.RETRY_WAIT;
        if (nextStatus == ExternalActionStatusEnum.RETRY_WAIT && next == null) {
            throw new IllegalArgumentException("RETRY_WAIT 必须具有 nextAttemptAt");
        }
        return copy(nextStatus, attemptCount, retryCycleAttemptCount, ExternalActionOutcomeEnum.FAILED,
                verificationAttemptCount, nextStatus == ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED ? null : next,
                null, null, code, message, now, null, version + 1);
    }

    public ExternalActionCommandModel failedPermanently(String code, String message, Instant now) {
        requireProcessing(now);
        return copy(ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED, attemptCount, retryCycleAttemptCount,
                ExternalActionOutcomeEnum.FAILED, verificationAttemptCount, null, null, null,
                code, message, now, null, version + 1);
    }

    /** 请求可能已产生副作用时只安排同幂等键核验重放，耗尽后保持 UNKNOWN。 */
    public ExternalActionCommandModel outcomeUnknownAt(Instant next, String code, String message, Instant now) {
        requireProcessing(now);
        int attempts = verificationAttemptCount + (outcome == ExternalActionOutcomeEnum.UNKNOWN ? 1 : 0);
        boolean exhausted = attempts >= maxVerificationAttempts;
        if (!exhausted && next == null) {
            throw new IllegalArgumentException("待核验结果必须具有 nextAttemptAt");
        }
        return copy(exhausted ? ExternalActionStatusEnum.MANUAL_VERIFICATION_REQUIRED
                        : ExternalActionStatusEnum.VERIFY_WAIT,
                attemptCount, retryCycleAttemptCount, ExternalActionOutcomeEnum.UNKNOWN, attempts,
                exhausted ? null : next, null, null, code, message, now, null, version + 1);
    }

    /** 将已知失败或待核实事项重新放回队列，保留原命令和幂等键。 */
    public ExternalActionCommandModel manualRetry(Instant now) {
        if (status != ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED
                && status != ExternalActionStatusEnum.MANUAL_VERIFICATION_REQUIRED) {
            throw new IllegalStateException("只有人工恢复状态允许重新入队");
        }
        if (now == null) {
            throw new IllegalArgumentException("人工重试时间不能为空");
        }
        boolean verify = outcome == ExternalActionOutcomeEnum.UNKNOWN;
        return copy(verify ? ExternalActionStatusEnum.VERIFY_WAIT : ExternalActionStatusEnum.PENDING,
                attemptCount, verify ? retryCycleAttemptCount : 0,
                verify ? ExternalActionOutcomeEnum.UNKNOWN : ExternalActionOutcomeEnum.PENDING,
                0, now, null, null, null, null, now, null, version + 1);
    }

    private ExternalActionCommandModel copy(
            ExternalActionStatusEnum nextStatus, int attempts, int cycleAttempts,
            ExternalActionOutcomeEnum nextOutcome, int verificationAttempts,
            Instant nextAttempt, String owner, Instant until, String errorCode, String errorMessage,
            Instant now, Instant finishedAt, long nextVersion
    ) {
        return new ExternalActionCommandModel(commandId, runId, threadId, turnId, userId, type,
                idempotencyKey, payloadJson, nextStatus, attempts, maxAttempts, nextAttempt, owner, until,
                errorCode, errorMessage, createdAt, now, finishedAt, nextVersion, cycleAttempts,
                nextOutcome, verificationAttempts, maxVerificationAttempts);
    }

    private void requireProcessing(Instant now) {
        if (status != ExternalActionStatusEnum.PROCESSING) {
            throw new IllegalStateException("完成或失败转换只允许从 PROCESSING 开始");
        }
        if (now == null) {
            throw new IllegalArgumentException("状态转换时间不能为空");
        }
    }

    private static ExternalActionOutcomeEnum legacyOutcome(ExternalActionStatusEnum status) {
        if (status == null) return ExternalActionOutcomeEnum.PENDING;
        return switch (status) {
            case SUCCEEDED -> ExternalActionOutcomeEnum.SUCCEEDED;
            case MANUAL_RETRY_REQUIRED, RETRY_WAIT -> ExternalActionOutcomeEnum.FAILED;
            case VERIFY_WAIT, MANUAL_VERIFICATION_REQUIRED -> ExternalActionOutcomeEnum.UNKNOWN;
            default -> ExternalActionOutcomeEnum.PENDING;
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
