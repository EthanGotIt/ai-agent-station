package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowOwnerRecoveryCandidate;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
                persistence, persistence, persistence,
                threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成：" + turn.input(), List.of(), null, false),
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
                persistence, persistence, persistence,
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
                persistence, persistence, persistence,
                threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成", List.of(), null, false),
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
                persistence, persistence, persistence,
                threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成", List.of(), null, false),
                new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        AgentTurnModel result = runtime.submitTurn(
                " user-1 ", thread.threadId(), " request-1 ", "新请求");

        assertEquals(raced.turnId(), result.turnId());
        scheduler.shutdownNow();
    }

    @Test
    void startupRecoveryConvergesAnsweredRejectedWorkflowOwner() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "恢复 Thread", null, null);
        AgentTurnModel owner = new AgentTurnModel(
                "owner-turn", thread.threadId(), "user-1", "owner-request", "申请退款",
                AgentTurnStatusEnum.WAITING_USER_INPUT, 1, "run-1", null, NOW, NOW, null);
        persistence.createTurn(owner);
        persistence.ownerRecoveryCandidates = List.of(
                new AgentWorkflowOwnerRecoveryCandidate(owner, AgentWorkflowStatusEnum.REJECTED, false));
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence,
                threads,
                new AgentContextAssembler(persistence, persistence, clock, 2_000, 1_000, 256, 128),
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成", List.of(), null, false),
                new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        runtime.recoverPersistedTurns();

        AgentTurnModel recovered = persistence.findTurn("user-1", owner.turnId()).orElseThrow();
        assertEquals(AgentTurnStatusEnum.COMPLETED, recovered.status());
        assertEquals("WORKFLOW_REJECTED", recovered.errorCode());
        assertTrue(persistence.items.stream().anyMatch(item -> item.type().name().equals("TURN_STATE")));
        scheduler.shutdownNow();
    }

    @Test
    void retriesContinuationUsingLatestPersistedTurnState() throws Exception {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "续跑重试 Thread", null, null);
        AgentContextAssembler context = new AgentContextAssembler(
                persistence, persistence, clock, 2_000, 1_000, 256, 128);
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AtomicInteger coordinatorCalls = new AtomicInteger();
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, threads, context,
                (current, turn, history, answer) -> {
                    coordinatorCalls.incrementAndGet();
                    return new AgentTurnCoordinator.AgentCoordinatorResult("完成", List.of(), null, false);
                }, new RecordingEvents(), executor, scheduler, clock,
                1, 4, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256);

        runtime.submitTurn("user-1", thread.threadId(), "blocking-request", "占用唯一排队位");
        AgentContinuationInput continuationInput = new AgentContinuationInput(
                "root-turn", "parent-turn", "run-1", "command-1", "SUCCEEDED", 1, 1);
        AgentTurnModel continuation = new AgentTurnModel(
                "continuation-turn", thread.threadId(), "user-1", "continuation-request", "自动续跑",
                AgentTurnStatusEnum.QUEUED, 1, "run-1", null, NOW, null, null, null, 0L,
                AgentTurnInputKindEnum.AGENT_CONTINUATION, null, continuationInput);
        persistence.createTurn(continuation);
        persistence.retryReadTurnId = continuation.turnId();
        persistence.retryReadLatch = new CountDownLatch(1);

        runtime.enqueuePersisted(continuation);
        AgentTurnModel cancelled = continuation.terminal(
                AgentTurnStatusEnum.CANCELLED, "CLIENT_CANCELLED", NOW.plusSeconds(1));
        assertTrue(persistence.updateTurn(continuation, cancelled));
        assertTrue(persistence.retryReadLatch.await(2, TimeUnit.SECONDS),
                "队列重试必须重新读取持久化 Turn 状态");

        executor.runAll();

        assertEquals(1, coordinatorCalls.get(), "已取消的续跑不得被过期快照重新入队");
        scheduler.shutdownNow();
    }

    @Test
    void doesNotEnqueueTurnCancelledBeforeAfterCommitCallback() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "回调竞态 Thread", null, null);
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AtomicInteger coordinatorCalls = new AtomicInteger();
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, threads,
                new AgentContextAssembler(persistence, persistence, clock, 2_000, 1_000, 256, 128),
                (current, turn, history, answer) -> {
                    coordinatorCalls.incrementAndGet();
                    return new AgentTurnCoordinator.AgentCoordinatorResult("完成", List.of(), null, false);
                }, new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256);
        AgentTurnModel turn = new AgentTurnModel(
                "callback-turn", thread.threadId(), "user-1", "callback-request", "回调续跑",
                AgentTurnStatusEnum.QUEUED, 1, null, null, NOW, null, null);
        persistence.createTurn(turn);
        AgentTurnModel cancelled = turn.terminal(
                AgentTurnStatusEnum.CANCELLED, "CLIENT_CANCELLED", NOW.plusSeconds(1));
        assertTrue(persistence.updateTurn(turn, cancelled));

        runtime.enqueuePersisted(turn);
        executor.runAll();

        assertEquals(0, coordinatorCalls.get(), "回调前已取消的 Turn 不得进入执行器");
        scheduler.shutdownNow();
    }

    @Test
    void deterministicOrderActionIsQueuedIdempotentlyWithoutModelCall() {
        InMemoryPersistence persistence = new InMemoryPersistence();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
        AgentThreadModel thread = threads.create("user-1", "订单动作 Thread", null, null);
        AgentTurnModel source = new AgentTurnModel(
                "source-turn", thread.threadId(), "user-1", "source-request", "查询订单",
                AgentTurnStatusEnum.COMPLETED, 1, null, null, NOW, NOW, NOW);
        persistence.createTurn(source);
        persistence.appendItem(new AgentItemModel("source-order-item", thread.threadId(), source.turnId(), 0,
                cn.ethan.core.agent.thread.AgentItemTypeEnum.ORDER_DETAIL,
                "{\"orderId\":\"order-1\"}", NOW));
        ManualExecutor executor = new ManualExecutor();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        AgentTurnRuntimeService runtime = new AgentTurnRuntimeService(
                persistence, persistence, persistence, threads,
                new AgentContextAssembler(persistence, persistence, clock, 2_000, 1_000, 256, 128),
                (current, turn, history, answer) -> {
                    throw new AssertionError("deterministic order action must not invoke model coordinator");
                },
                new RecordingEvents(), executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256,
                AgentRuntimeMetrics.noop(),
                (current, turn, history, input, executionContext) ->
                        new AgentTurnCoordinator.AgentCoordinatorResult("", List.of(
                                new AgentTurnCoordinator.AgentItemDraft(
                                        "ORDER_DETAIL", "{\"orderId\":\"order-1\"}")),
                                 null, false),
                false, 3, null, null);

        AgentTurnModel action = runtime.submitOrderAction("user-1", thread.threadId(), "action-request",
                source.turnId(), "order-1", AgentOrderActionTypeEnum.REFRESH_ORDER);
        AgentTurnModel duplicate = runtime.submitOrderAction("user-1", thread.threadId(), "action-request",
                source.turnId(), "order-1", AgentOrderActionTypeEnum.REFRESH_ORDER);

        assertEquals(action.turnId(), duplicate.turnId());
        assertEquals(cn.ethan.core.agent.thread.AgentTurnInputKindEnum.ORDER_ACTION, action.inputKind());
        assertThrows(cn.ethan.core.agent.thread.AgentThreadConflictException.class,
                () -> runtime.submitOrderAction("user-1", thread.threadId(), "action-request",
                        source.turnId(), "order-1", AgentOrderActionTypeEnum.QUERY_LOGISTICS));
        executor.runAll();
        assertEquals(AgentTurnStatusEnum.COMPLETED,
                persistence.findTurn("user-1", action.turnId()).orElseThrow().status());
        assertTrue(persistence.items.stream().anyMatch(item -> item.type()
                == cn.ethan.core.agent.thread.AgentItemTypeEnum.ORDER_ACTION_REQUEST));
        assertTrue(persistence.items.stream().anyMatch(item -> item.turnId().equals(action.turnId())
                && item.type() == cn.ethan.core.agent.thread.AgentItemTypeEnum.ORDER_DETAIL),
                "deterministic order facts must be persisted on the action Turn");
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
            AgentItemStore, AgentContextSnapshotStore {
        private final Map<String, AgentThreadModel> threads = new HashMap<>();
        private final Map<String, AgentTurnModel> turns = new HashMap<>();
        private final List<AgentItemModel> items = new ArrayList<>();
        private AgentTurnModel raceOnCreation;
        private List<AgentWorkflowOwnerRecoveryCandidate> ownerRecoveryCandidates = List.of();
        private String retryReadTurnId;
        private CountDownLatch retryReadLatch;

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
            if (turnId.equals(retryReadTurnId) && retryReadLatch != null) {
                retryReadLatch.countDown();
            }
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
        public List<AgentWorkflowOwnerRecoveryCandidate> listWorkflowOwnerRecoveryCandidates() {
            return ownerRecoveryCandidates;
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
        public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
            return Optional.empty();
        }

        @Override
        public void saveSnapshot(AgentContextSnapshotModel snapshot) {
            throw new UnsupportedOperationException("test does not persist snapshots");
        }
    }
}
