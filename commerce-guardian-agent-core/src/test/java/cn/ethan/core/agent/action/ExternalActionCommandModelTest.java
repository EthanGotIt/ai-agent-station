package cn.ethan.core.agent.action;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 外部动作契约测试：验证总尝试次数、人工重试周期和版本栅栏的状态转换。
 *
 * @author ethan
 * @date 2026-08-20
 */
class ExternalActionCommandModelTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void claimCountsTotalAndCurrentCycleAndAdvancesVersion() {
        ExternalActionCommandModel command = command(0, 0, 0, ExternalActionStatusEnum.PENDING);

        ExternalActionCommandModel claimed = command.claimed("worker-1", NOW.plusSeconds(30), NOW);

        assertEquals(1, claimed.attemptCount());
        assertEquals(1, claimed.totalAttemptCount());
        assertEquals(1, claimed.retryCycleAttemptCount());
        assertEquals(1, claimed.version());
        assertNull(claimed.nextAttemptAt());
    }

    @Test
    void manualRetryKeepsIdentityAndTotalButResetsCurrentCycle() {
        ExternalActionCommandModel command = command(3, 3, 2, ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED);

        ExternalActionCommandModel retried = command.manualRetry(NOW);

        assertEquals(command.commandId(), retried.commandId());
        assertEquals(command.idempotencyKey(), retried.idempotencyKey());
        assertEquals(3, retried.totalAttemptCount());
        assertEquals(0, retried.retryCycleAttemptCount());
        assertEquals(3, retried.version());
        assertEquals(ExternalActionStatusEnum.PENDING, retried.status());
        assertEquals(NOW, retried.nextAttemptAt());
    }

    @Test
    void exhaustedRetryCycleHasNullableNextAttemptTime() {
        ExternalActionCommandModel claimed = command(3, 3, 1, ExternalActionStatusEnum.PROCESSING)
                .claimed("worker-1", NOW.plusSeconds(30), NOW);

        ExternalActionCommandModel failed = claimed.retryAt(NOW.plusSeconds(60), "TIMEOUT", "超时", NOW);

        assertEquals(ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED, failed.status());
        assertNull(failed.nextAttemptAt());
        assertEquals(3, failed.version());
    }

    @Test
    void rejectsIllegalStateFieldMatrix() {
        assertThrows(IllegalArgumentException.class, () -> new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.PENDING, 0, 3, null,
                null, null, null, null, NOW, NOW, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.PROCESSING, 1, 3, null,
                null, NOW.plusSeconds(30), null, null, NOW, NOW, null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.SUCCEEDED, 1, 3, NOW,
                null, null, null, null, NOW, NOW, NOW, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED, 3, 3, NOW,
                null, null, null, null, NOW, NOW, null, 3, 3));
    }

    @Test
    void completionAndFailureTransitionsRequireProcessing() {
        ExternalActionCommandModel pending = command(0, 0, 0, ExternalActionStatusEnum.PENDING);

        assertThrows(IllegalStateException.class, () -> pending.succeeded(NOW));
        assertThrows(IllegalStateException.class,
                () -> pending.retryAt(NOW.plusSeconds(30), "E", "error", NOW));
        assertThrows(IllegalStateException.class, () -> pending.failedPermanently("E", "error", NOW));
        assertThrows(IllegalStateException.class, () -> pending.manualRetry(NOW));
    }

    private ExternalActionCommandModel command(int totalAttempts, int cycleAttempts, long version,
                                               ExternalActionStatusEnum status) {
        Instant nextAttemptAt = status == ExternalActionStatusEnum.PENDING
                || status == ExternalActionStatusEnum.RETRY_WAIT ? NOW : null;
        String leaseOwner = status == ExternalActionStatusEnum.PROCESSING ? "worker-previous" : null;
        Instant leaseUntil = status == ExternalActionStatusEnum.PROCESSING ? NOW : null;
        Instant completedAt = status == ExternalActionStatusEnum.SUCCEEDED ? NOW : null;
        return new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", status, totalAttempts, 3, nextAttemptAt, leaseOwner, leaseUntil, "E", "error",
                NOW, NOW, completedAt, version, cycleAttempts);
    }
}
