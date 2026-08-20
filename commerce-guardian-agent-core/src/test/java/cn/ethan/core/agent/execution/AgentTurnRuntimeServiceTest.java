package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
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
                persistence, persistence, persistence, persistence, threads, context,
                (current, turn, history, answer) -> new AgentTurnCoordinator.AgentCoordinatorResult(
                        "完成：" + turn.input(), List.of(), null, null, false),
                events, executor, scheduler, clock,
                4, 16, java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(5), 256
        );

        AgentTurnModel first = runtime.submitTurn("user-1", thread.threadId(), "request-1", "第一条");
        AgentTurnModel second = runtime.submitTurn("user-1", thread.threadId(), "request-2", "第二条");

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
        scheduler.shutdownNow();
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
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
        public void updateTurn(AgentTurnModel turn) {
            turns.put(turn.turnId(), turn);
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
        public void answerQuestion(AgentWorkflowQuestionModel question) {
            throw new UnsupportedOperationException("test does not answer a workflow");
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
