package cn.ethan.core.agent.thread;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证 Thread 身份在进入 Core 时即受数据库主键和归属列边界约束。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentThreadIdentityTest {

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

    private AgentThreadModel thread(String threadId, String userId) {
        return new AgentThreadModel(threadId, userId, "title", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, Instant.EPOCH, Instant.EPOCH);
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
}
