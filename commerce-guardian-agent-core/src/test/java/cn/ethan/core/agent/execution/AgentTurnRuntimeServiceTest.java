package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime 测试：验证同一 Thread 的 FIFO、排队取消和完成事实持久化。
 *
 * @author ethan
 * @date 2026-08-20
 */
class AgentTurnRuntimeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void cancelsQueuedTurnAndRunsNextTurnInOrder() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "测试 Thread", null, null);
        AgentContextAssembler context = new AgentContextAssembler(
                persistence, persistence, clock, 2_000, 1_000, 256, 128);
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RecordingEvents events = new RecordingEvents();
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, persistence,
                command -> { throw new UnsupportedOperationException("test does not answer a workflow"); },
                (turn, status, code, finishedAt) -> false,
                threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成：" + turn.input(), List.of(), null, null, false),
                events, executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        AgentTurnModel first = runtime.submitTurn("user-1", thread.threadId(), "request-1", "第一条");
        AgentTurnModel duplicate = runtime.submitTurn("user-1", thread.threadId(), "request-1", "第一条");
        AgentTurnModel second = runtime.submitTurn("user-1", thread.threadId(), "request-2", "第二条");

        assertEquals(first.turnId(), duplicate.turnId());
        assertEquals(AgentTurnStatusEnum.QUEUED, persistence.findTurnByRequest("user-1", first.clientRequestId())
                .orElseThrow().status());
        assertEquals(AgentTurnStatusEnum.QUEUED, second.status());
        assertTrue(runtime.cancel("user-1", first.turnId()));
        assertEquals(AgentTurnStatusEnum.CANCELLED, persistence.findTurnByRequest("user-1", first.clientRequestId())
                .orElseThrow().status());

        executor.runAll();

        assertEquals(AgentTurnStatusEnum.COMPLETED, persistence.findTurnByRequest("user-1", second.clientRequestId())
                .orElseThrow().status());
        assertTrue(events.published.stream().anyMatch(event -> event.type().equals("item.assistant_message")));
        assertTrue(events.published.stream().anyMatch(event -> event.type().equals("item.turn_state")));
        scheduler.shutdownNow();
    }

    @Test
    void persistsTimeoutWhenCoordinatorReportsDeadline() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "超时 Thread", null, null);
        AgentContextAssembler context = new AgentContextAssembler(
                persistence, persistence, clock, 2_000, 1_000, 256, 128);
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, persistence,
                command -> { throw new UnsupportedOperationException("test does not answer a workflow"); },
                (turn, status, code, finishedAt) -> false,
                threads, context,
                (current, turn, history, answer) -> {
                    throw new AgentExecutionTimeoutException("模型流式调用超时");
                },
                new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        AgentTurnModel turn = runtime.submitTurn("user-1", thread.threadId(), "timeout-request", "超时请求");
        executor.runAll();

        AgentTurnModel persisted = persistence.findTurnByRequest("user-1", turn.clientRequestId()).orElseThrow();
        assertEquals(AgentTurnStatusEnum.TIMED_OUT, persisted.status());
        assertEquals("TURN_TIMEOUT", persisted.errorCode());
        scheduler.shutdownNow();
    }

    @Test
    void persistsNormalizedMessageInInitialUserItem() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "规范化 Thread", null, null);
        AgentContextAssembler context = new AgentContextAssembler(
                persistence, persistence, clock, 2_000, 1_000, 256, 128);
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, persistence,
                command -> { throw new UnsupportedOperationException("test does not answer a workflow"); },
                (turn, status, code, finishedAt) -> false,
                threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成", List.of(), null, null, false),
                new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        AgentTurnModel turn = runtime.submitTurn(
                " user-1 ", thread.threadId(), " request-1 ", "  规范化消息  ");

        AgentItemModel userItem = persistence.items.stream()
                .filter(item -> turn.turnId().equals(item.turnId()))
                .findFirst()
                .orElseThrow();
        assertTrue(userItem.payloadJson().contains("规范化消息"));
        assertTrue(!userItem.payloadJson().contains("  规范化消息  "));
        scheduler.shutdownNow();
    }

    @Test
    void resolvesCreationRaceWithNormalizedOwnerAndRequestId() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "竞态 Thread", null, null);
        AgentContextAssembler context = new AgentContextAssembler(
                persistence, persistence, clock, 2_000, 1_000, 256, 128);
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentTurnModel raced = new AgentTurnModel(
                "raced-turn", thread.threadId(), "user-1", "request-1", "已存在",
                AgentTurnStatusEnum.QUEUED, 1, null, null, NOW, null, null);
        persistence.raceOnCreation = raced;
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, persistence,
                command -> { throw new UnsupportedOperationException("test does not answer a workflow"); },
                (turn, status, code, finishedAt) -> false,
                threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成", List.of(), null, null, false),
                new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        AgentTurnModel result = runtime.submitTurn(
                " user-1 ", thread.threadId(), " request-1 ", "新请求");

        assertEquals(raced.turnId(), result.turnId());
        scheduler.shutdownNow();
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(@NonNull Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }
    }

    private static final class RecordingEvents implements AgentThreadEventGateway {
        private final List<AgentThreadEvent> published = new ArrayList<>();

        @Override
        public void publish(AgentThreadEvent event) {
            published.add(event);
        }
    }

    private static final class InMemoryPersistence implements AgentThreadStore, AgentTurnStore,
            AgentItemStore, AgentWorkflowQuestionStore, AgentContextSnapshotStore {
        private final Map<String, AgentThreadModel> threads = new HashMap<>();
        private final Map<String, AgentTurnModel> turns = new HashMap<>();
        private final List<AgentItemModel> items = new ArrayList<>();
        private AgentTurnModel raceOnCreation;

        @Override
        public void createThread(AgentThreadModel thread) {
            threads.put(thread.threadId(), thread);
        }

        @Override
        public Optional<AgentThreadModel> findThread(String userId, String threadId) {
            return Optional.ofNullable(threads.get(threadId))
                    .filter(thread -> thread.userId().equals(userId));
        }

        @Override
        public List<AgentThreadModel> listThreads(String userId) {
            return threads.values().stream().filter(thread -> thread.userId().equals(userId)).toList();
        }

        @Override
        public void updateThread(AgentThreadModel thread) {
            threads.put(thread.threadId(), thread);
        }

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            return Optional.ofNullable(turns.get(turnId))
                    .filter(turn -> turn.userId().equals(userId));
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return turns.values().stream()
                    .filter(turn -> turn.userId().equals(userId) && turn.clientRequestId().equals(clientRequestId))
                    .findFirst();
        }

        @Override
        public void createTurn(AgentTurnModel turn) {
            turns.put(turn.turnId(), turn);
        }

        @Override
        public long createTurnWithInitialItem(AgentTurnModel turn, AgentItemModel initialItem) {
            if (raceOnCreation != null) {
                turns.put(raceOnCreation.turnId(), raceOnCreation);
                raceOnCreation = null;
                throw new IllegalStateException("simulated duplicate request");
            }
            createTurn(turn);
            return 0L;
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
            AgentTurnModel current = turns.get(expected.turnId());
            if (current == null || current.version() != expected.version()
                    || next.version() != expected.version() + 1) {
                return false;
            }
            turns.put(next.turnId(), next);
            return true;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return turns.values().stream()
                    .filter(turn -> turn.status() == AgentTurnStatusEnum.QUEUED
                            || turn.status() == AgentTurnStatusEnum.ACTIVE)
                    .toList();
        }

        @Override
        public long appendItem(AgentItemModel item) {
            long sequence = items.stream().filter(value -> value.threadId().equals(item.threadId()))
                    .mapToLong(AgentItemModel::sequence).max().orElse(0) + 1;
            items.add(new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                    item.type(), item.payload(), item.createdAt()));
            return sequence;
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return items.stream().filter(item -> item.threadId().equals(threadId) && item.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(AgentItemModel::sequence)).limit(limit).toList();
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
            return Optional.empty();
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
            return Optional.empty();
        }

        @Override
        public void saveQuestion(AgentWorkflowQuestionModel question) {
            throw new UnsupportedOperationException("test does not start a workflow");
        }

        @Override
        public OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion,
                                              String answerTurnId) { return OptionalLong.empty(); }

        @Override
        public OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion,
                                                   String answerTurnId) { return OptionalLong.empty(); }

        @Override
        public boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion,
                                         String answerTurnId) { return false; }

        @Override
        public boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                                       String answerTurnId, Instant answeredAt) { return false; }

        @Override
        public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
            return Optional.empty();
        }

        @Override
        public void saveSnapshot(AgentContextSnapshotModel snapshot) {
            throw new UnsupportedOperationException("test does not persist snapshots");
        }
    }
}
