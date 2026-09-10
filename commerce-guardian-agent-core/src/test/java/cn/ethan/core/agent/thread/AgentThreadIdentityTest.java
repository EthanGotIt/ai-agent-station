package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.execution.AgentQuestionAnswerAdmissionCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Thread 与 Turn 契约测试：验证身份、请求幂等键和生命周期版本在 Core 边界上的不变量。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentThreadIdentityTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void acceptsDatabaseBoundaries() {
        assertDoesNotThrow(() -> thread("t".repeat(64), "u".repeat(128)));
        assertDoesNotThrow(() -> new AgentThreadModel("thread-1", "user-1", "t".repeat(256),
                AgentThreadStatusEnum.ACTIVE, "c".repeat(64), "i".repeat(128),
                0, Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void rejectsIdsBeyondDatabaseBoundaries() {
        assertThrows(IllegalArgumentException.class, () -> thread("t".repeat(65), "user-1"));
        assertThrows(IllegalArgumentException.class, () -> thread("thread-1", "u".repeat(129)));
        assertThrows(IllegalArgumentException.class, () -> new AgentThreadModel("thread-1", "user-1",
                "t".repeat(257), AgentThreadStatusEnum.ACTIVE, null, null, 0,
                Instant.EPOCH, Instant.EPOCH));
    }

    @Test
    void rejectsPageOffsetOverflowAsInvalidInput() {
        AgentThreadService service = new AgentThreadService(
                new EmptyThreadStore(), new EmptyItemStore(), java.time.Clock.systemUTC());

        assertThrows(IllegalArgumentException.class,
                () -> service.listPage("user-1", Integer.MAX_VALUE, 100));
    }

    @Test
    void filtersLifecycleAndKeepsHistoricalArchiveReadOnlyDuringRename() {
        AgentThreadModel active = thread("active-thread", "user-1");
        AgentThreadModel archived = new AgentThreadModel("archived-thread", "user-1", "old",
                AgentThreadStatusEnum.ARCHIVED, null, null, 0, Instant.EPOCH, Instant.EPOCH);
        LifecycleThreadStore store = new LifecycleThreadStore(active, archived);
        AgentThreadService service = new AgentThreadService(
                store, new EmptyItemStore(), java.time.Clock.systemUTC());

        assertEquals(1, service.listPage("user-1", AgentThreadStatusEnum.ACTIVE, 0, 20).total());
        assertEquals(1, service.listPage("user-1", AgentThreadStatusEnum.ARCHIVED, 0, 20).total());

        AgentThreadModel updated = service.update("user-1", "archived-thread", "renamed");
        assertEquals("renamed", updated.title());
        assertEquals(AgentThreadStatusEnum.ARCHIVED, updated.status());
        assertEquals(1, service.listPage("user-1", AgentThreadStatusEnum.ARCHIVED, 0, 20).total());
    }

    @Test
    void accepts128AndRejects129CharactersForTurnAndAnswer() {
        String accepted = "a".repeat(128);
        String rejected = "a".repeat(129);

        assertDoesNotThrow(() -> turn(accepted));
        assertThrows(IllegalArgumentException.class, () -> turn(rejected));
        assertDoesNotThrow(() -> answerCommand(accepted));
        assertThrows(IllegalArgumentException.class, () -> answerCommand(rejected));
    }

    @Test
    void normalizesRequestIdBeforePersistenceAndAdmissionLookup() {
        assertEquals("request-1", turn("  request-1  ").clientRequestId());
        assertEquals("request-1", answerCommand("  request-1  ").clientRequestId());
    }

    @Test
    void lifecycleTransitionsAdvanceExactlyOneVersion() {
        AgentTurnModel queued = turn();

        AgentTurnModel active = queued.active(NOW.plusSeconds(1));
        AgentTurnModel waiting = active.workflow("run-1", AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION);
        AgentTurnModel completed = waiting.terminal(AgentTurnStatusEnum.COMPLETED, null, NOW.plusSeconds(2));

        assertEquals(0L, queued.version());
        assertEquals(1L, active.version());
        assertEquals(2L, waiting.version());
        assertEquals(3L, completed.version());
    }

    @Test
    void negativeVersionIsRejectedInsteadOfNormalized() {
        assertThrows(IllegalArgumentException.class, () -> new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "message",
                AgentTurnStatusEnum.QUEUED, 0, null, null, NOW, null, null, null, -1L));
    }

    private AgentThreadModel thread(String threadId, String userId) {
        return new AgentThreadModel(threadId, userId, "title", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, Instant.EPOCH, Instant.EPOCH);
    }

    private AgentTurnModel turn(String clientRequestId) {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", clientRequestId, "message",
                AgentTurnStatusEnum.QUEUED, 1, null, null, Instant.EPOCH, null, null);
    }

    private AgentTurnModel turn() {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "message",
                AgentTurnStatusEnum.QUEUED, 0, null, null, NOW, null, null);
    }

    private AgentQuestionAnswerAdmissionCommand answerCommand(String clientRequestId) {
        return new AgentQuestionAnswerAdmissionCommand(
                "user-1", "question-1", clientRequestId, 0, Map.of("answer", "value"), null);
    }

    private static final class EmptyThreadStore implements AgentThreadStore {
        @Override public void createThread(AgentThreadModel thread) { }
        @Override public Optional<AgentThreadModel> findThread(String userId, String threadId) {
            return Optional.empty();
        }
        @Override public List<AgentThreadModel> listThreads(String userId) { return List.of(); }
        @Override public void updateThread(AgentThreadModel thread) { }
    }

    private static final class EmptyItemStore implements AgentItemStore {
        @Override public long appendItem(AgentItemModel item) { return 1L; }
        @Override public List<AgentItemModel> listItems(String userId, String threadId,
                                                        long afterSequence, int limit) {
            return List.of();
        }
    }

    private static final class LifecycleThreadStore implements AgentThreadStore {
        private final List<AgentThreadModel> values;

        private LifecycleThreadStore(AgentThreadModel... values) {
            this.values = new java.util.ArrayList<>(List.of(values));
        }

        @Override public void createThread(AgentThreadModel thread) { values.add(thread); }
        @Override public Optional<AgentThreadModel> findThread(String userId, String threadId) {
            return values.stream().filter(value -> value.userId().equals(userId)
                    && value.threadId().equals(threadId)).findFirst();
        }
        @Override public List<AgentThreadModel> listThreads(String userId) {
            return values.stream().filter(value -> value.userId().equals(userId)).toList();
        }
        @Override public void updateThread(AgentThreadModel thread) {
            values.removeIf(value -> value.threadId().equals(thread.threadId()));
            values.add(thread);
        }
    }
}
