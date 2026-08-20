package cn.ethan.core.agent.action;

import cn.ethan.core.agent.thread.AgentThreadConflictException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 人工重试契约测试：CAS 冲突必须显式返回，且不能伪装成重试成功。
 *
 * @author ethan
 * @date 2026-08-20
 */
class ExternalActionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void manualRetryCasConflictIsExplicit() {
        ExternalActionCommandModel command = manualRetryRequired();
        RejectingStore store = new RejectingStore(command);
        ExternalActionService service = new ExternalActionService(
                store, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));

        AgentThreadConflictException conflict = assertThrows(
                AgentThreadConflictException.class, () -> service.retry("user-1", "run-1"));

        assertEquals("ACTION_VERSION_CONFLICT", conflict.code());
        assertEquals(command, store.expected);
        assertEquals(command.commandId(), store.next.commandId());
        assertEquals(command.idempotencyKey(), store.next.idempotencyKey());
        assertEquals(command.attemptCount(), store.next.attemptCount());
        assertEquals(0, store.next.retryCycleAttemptCount());
    }

    private ExternalActionCommandModel manualRetryRequired() {
        return new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", "{}", ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED, 3, 3, null,
                null, null, "FAILED", "failed", NOW, NOW, null, 4, 3);
    }

    private static final class RejectingStore implements ExternalActionCommandStore {

        private final ExternalActionCommandModel command;
        private ExternalActionCommandModel expected;
        private ExternalActionCommandModel next;

        private RejectingStore(ExternalActionCommandModel command) {
            this.command = command;
        }

        @Override
        public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command) {
            return command;
        }

        @Override
        public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
            return Optional.empty();
        }

        @Override
        public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
            return Optional.of(command);
        }

        @Override
        public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<ExternalActionCommandModel> claimDue(
                Instant now, Instant leaseUntil, String workerId, int limit) {
            return List.of();
        }

        @Override
        public boolean update(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
            this.expected = expected;
            this.next = next;
            return false;
        }
    }
}
