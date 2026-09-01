package cn.ethan.infrastructure.agent.action.worker;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Worker 契约测试：失去 Lease 的旧 Worker 不得继续投影任何本地状态。
 *
 * @author ethan
 * @date 2026-08-20
 */
class ExternalActionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void casFailureStopsWorkflowTurnItemAndEventProjection() {
        RejectingCommandStore commands = new RejectingCommandStore(claimed());
        CountingItemStore items = new CountingItemStore();
        CountingTurnStore turns = new CountingTurnStore();
        CountingWorkflowRunStore workflowRuns = new CountingWorkflowRunStore();
        AtomicInteger eventCount = new AtomicInteger();
        AtomicInteger executorCount = new AtomicInteger();
        AgentThreadEventGateway events = event -> eventCount.incrementAndGet();
        ExternalActionExecutor executor = command -> {
            executorCount.incrementAndGet();
            return new ExternalActionExecutor.ExternalActionResult(true, false, "OK", "done");
        };
        ExternalActionWorker worker = new ExternalActionWorker(
                commands, executor, items, turns, events, Clock.fixed(NOW, ZoneOffset.UTC), workflowRuns,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), AgentRuntimeMetrics.noop());

        try {
            assertEquals(1, worker.runOnce(1, Duration.ofSeconds(30)));
        } finally {
            worker.destroy();
        }

        assertEquals(1, executorCount.get());
        assertEquals(1, commands.updateCount.get());
        assertEquals(commands.claimed, commands.expected);
        assertEquals(0, workflowRuns.findCount.get());
        assertEquals(0, workflowRuns.updateCount.get());
        assertEquals(0, turns.findCount.get());
        assertEquals(0, turns.updateCount.get());
        assertEquals(0, items.appendCount.get());
        assertEquals(0, eventCount.get());
    }

    @Test
    void successfulActionCommitsCommandAndAllLocalFactsBeforePublishing() {
        ExternalActionCommandModel claimed = claimed();
        AcceptingCommandStore commands = new AcceptingCommandStore(claimed);
        CountingItemStore items = new CountingItemStore();
        AgentTurnModel waitingTurn = new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "refund",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION, 0,
                "run-1", null, NOW.minusSeconds(10), NOW.minusSeconds(5), null);
        CountingTurnStore turns = new CountingTurnStore(waitingTurn);
        AgentWorkflowRunModel waitingRun = new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 0, NOW.minusSeconds(10), NOW.minusSeconds(5));
        CountingWorkflowRunStore workflowRuns = new CountingWorkflowRunStore(waitingRun);
        AtomicInteger eventCount = new AtomicInteger();
        ExternalActionWorker worker = new ExternalActionWorker(
                commands, command -> new ExternalActionExecutor.ExternalActionResult(
                        true, false, "ORDER_REFUNDED", "订单已退款"), items, turns,
                event -> eventCount.incrementAndGet(), Clock.fixed(NOW, ZoneOffset.UTC), workflowRuns,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), AgentRuntimeMetrics.noop());

        try {
            assertEquals(1, worker.runOnce(1, Duration.ofSeconds(30)));
        } finally {
            worker.destroy();
        }

        assertEquals(ExternalActionStatusEnum.SUCCEEDED, commands.next.status());
        assertEquals(AgentWorkflowStatusEnum.COMPLETED, workflowRuns.updated.status());
        assertEquals(cn.ethan.core.agent.thread.AgentTurnStatusEnum.COMPLETED, turns.updated.status());
        assertEquals(2, items.appended.size());
        assertEquals(2, eventCount.get());
        items.appended.forEach(item -> {
            assertTrue(item.payloadJson().contains("\"schemaVersion\":1"));
            assertFalse(item.payloadJson().contains("command-1|"));
        });
    }

    @Test
    void successfulActionVerifiesLatestFactsAndProjectsThemIntoTheSameTurn() {
        ExternalActionCommandModel claimed = claimed("{\"orderId\":\"order-1\"}");
        AcceptingCommandStore commands = new AcceptingCommandStore(claimed);
        CountingItemStore items = new CountingItemStore();
        AgentTurnModel waitingTurn = new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "refund",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION, 0,
                "run-1", null, NOW.minusSeconds(10), NOW.minusSeconds(5), null);
        CountingTurnStore turns = new CountingTurnStore(waitingTurn);
        AgentWorkflowRunModel waitingRun = new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 0, NOW.minusSeconds(10), NOW.minusSeconds(5));
        CountingWorkflowRunStore workflowRuns = new CountingWorkflowRunStore(waitingRun);
        AtomicInteger eventCount = new AtomicInteger();
        OrderSnapshotModel order = new OrderSnapshotModel("order-1", "user-1", "REFUNDED", 0);
        LogisticsEventModel logisticsEvent = new LogisticsEventModel(
                "log-1", "order-1", "REFUNDED", "仓库", "退款完成", NOW);
        ExternalActionWorker worker = new ExternalActionWorker(
                commands, command -> new ExternalActionExecutor.ExternalActionResult(
                        true, false, "ORDER_REFUNDED", "订单已退款"), items, turns,
                event -> eventCount.incrementAndGet(), Clock.fixed(NOW, ZoneOffset.UTC), workflowRuns,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), AgentRuntimeMetrics.noop(),
                (orderId, userId) -> new cn.ethan.core.commerce.order.OrderLookupResultModel(
                        cn.ethan.core.commerce.order.OrderLookupStatusEnum.FOUND, order),
                (orderId, userId) -> List.of(logisticsEvent));

        try {
            assertEquals(1, worker.runOnce(1, Duration.ofSeconds(30)));
        } finally {
            worker.destroy();
        }

        assertEquals(4, items.appended.size());
        assertEquals(4, eventCount.get());
        assertTrue(items.appended.get(0).payloadJson().contains("\"verificationStatus\":\"VERIFIED\""));
        assertTrue(items.appended.stream().anyMatch(item -> item.type().name().equals("ORDER_DETAIL")));
        assertTrue(items.appended.stream().anyMatch(item -> item.type().name().equals("LOGISTICS_TIMELINE")));
    }

    @Test
    void successfulActionDoesNotClaimVerificationWhenFactsDoNotMatch() {
        ExternalActionCommandModel claimed = claimed("{\"orderId\":\"order-1\"}");
        AcceptingCommandStore commands = new AcceptingCommandStore(claimed);
        CountingItemStore items = new CountingItemStore();
        AgentTurnModel waitingTurn = new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "refund",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION, 0,
                "run-1", null, NOW.minusSeconds(10), NOW.minusSeconds(5), null);
        CountingTurnStore turns = new CountingTurnStore(waitingTurn);
        AgentWorkflowRunModel waitingRun = new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 0, NOW.minusSeconds(10), NOW.minusSeconds(5));
        CountingWorkflowRunStore workflowRuns = new CountingWorkflowRunStore(waitingRun);
        OrderSnapshotModel order = new OrderSnapshotModel("order-1", "user-1", "PAID", 0);
        ExternalActionWorker worker = new ExternalActionWorker(
                commands, command -> new ExternalActionExecutor.ExternalActionResult(
                        true, false, "ORDER_REFUNDED", "订单已退款"), items, turns,
                event -> { }, Clock.fixed(NOW, ZoneOffset.UTC), workflowRuns,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), AgentRuntimeMetrics.noop(),
                (orderId, userId) -> OrderLookupResultModel.found(order),
                (orderId, userId) -> List.of());

        try {
            assertEquals(1, worker.runOnce(1, Duration.ofSeconds(30)));
        } finally {
            worker.destroy();
        }

        assertTrue(items.appended.stream().anyMatch(item ->
                item.payloadJson().contains("\"verificationStatus\":\"PENDING\"")));
        assertTrue(items.appended.stream().anyMatch(item ->
                item.payloadJson().contains("\"verificationMessage\":\"操作已受理、最新状态暂未核验\"")));
    }

    @Test
    void manualRetryReopensWorkflowRunWithoutRewritingFailedTurn() {
        ExternalActionCommandModel claimed = claimed();
        AcceptingCommandStore commands = new AcceptingCommandStore(claimed);
        CountingItemStore items = new CountingItemStore();
        CountingTurnStore turns = new CountingTurnStore(new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "refund",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.FAILED, 0,
                "run-1", "EXTERNAL_ACTION_FAILED", NOW.minusSeconds(10), NOW.minusSeconds(5), NOW));
        CountingWorkflowRunStore workflowRuns = new CountingWorkflowRunStore(new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED, 1, NOW.minusSeconds(10), NOW.minusSeconds(1)));
        AtomicInteger eventCount = new AtomicInteger();
        ExternalActionWorker worker = new ExternalActionWorker(
                commands, command -> new ExternalActionExecutor.ExternalActionResult(
                        true, false, "ORDER_REFUNDED", "人工重试成功"), items,
                turns, event -> eventCount.incrementAndGet(), Clock.fixed(NOW, ZoneOffset.UTC), workflowRuns,
                Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5), AgentRuntimeMetrics.noop());

        try {
            assertEquals(1, worker.runOnce(1, Duration.ofSeconds(30)));
        } finally {
            worker.destroy();
        }

        assertEquals(ExternalActionStatusEnum.SUCCEEDED, commands.next.status());
        assertEquals(AgentWorkflowStatusEnum.COMPLETED, workflowRuns.updated.status());
        assertEquals(0, turns.updateCount.get());
        assertEquals(1, items.appended.size());
        assertEquals(1, eventCount.get());
    }

    @Test
    void localProjectionFailureRollsBackCommandAndWorkflowState() {
        ExternalActionCommandModel claimed = claimed();
        AcceptingCommandStore commands = new AcceptingCommandStore(claimed);
        CountingTurnStore turns = new CountingTurnStore(new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "refund",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION, 0,
                "run-1", null, NOW.minusSeconds(10), NOW.minusSeconds(5), null));
        CountingWorkflowRunStore workflowRuns = new CountingWorkflowRunStore(new AgentWorkflowRunModel(
                "run-1", "thread-1", "turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, 0, NOW.minusSeconds(10), NOW.minusSeconds(5)));
        RecordingTransactionManager transactions = new RecordingTransactionManager(() -> {
            commands.next = null;
            workflowRuns.updated = null;
        });
        ExternalActionOutcomeManager manager = new ExternalActionOutcomeManager(
                commands, new ThrowingItemStore(), turns, workflowRuns, new ObjectMapper(), transactions);

        assertThrows(IllegalStateException.class, () -> manager.transition(
                claimed, claimed.succeeded(NOW), "OK", "done", Clock.fixed(NOW, ZoneOffset.UTC)));

        assertEquals(1, transactions.rollbackCount);
        assertNull(commands.next);
        assertNull(workflowRuns.updated);
        assertEquals(0, turns.updateCount.get());
    }

    private ExternalActionCommandModel claimed() {
        return claimed("{}");
    }

    private ExternalActionCommandModel claimed(String payload) {
        return new ExternalActionCommandModel(
                "command-1", "run-1", "thread-1", "turn-1", "user-1", ExternalActionTypeEnum.REFUND,
                "idem-1", payload, ExternalActionStatusEnum.PROCESSING, 1, 3, null,
                "worker-stale", NOW.plusSeconds(30), null, null, NOW.minusSeconds(10), NOW, null, 1, 1);
    }

    private static final class RejectingCommandStore implements ExternalActionCommandStore {

        private final ExternalActionCommandModel claimed;
        private final AtomicInteger updateCount = new AtomicInteger();
        private ExternalActionCommandModel expected;

        private RejectingCommandStore(ExternalActionCommandModel claimed) {
            this.claimed = claimed;
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
            return Optional.empty();
        }

        @Override
        public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<ExternalActionCommandModel> claimDue(
                Instant now, Instant leaseUntil, String workerId, int limit) {
            return List.of(claimed);
        }

        @Override
        public boolean update(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
            this.expected = expected;
            updateCount.incrementAndGet();
            return false;
        }
    }

    private static final class AcceptingCommandStore implements ExternalActionCommandStore {

        private final ExternalActionCommandModel claimed;
        private ExternalActionCommandModel next;

        private AcceptingCommandStore(ExternalActionCommandModel claimed) {
            this.claimed = claimed;
        }

        @Override
        public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command) {
            return command;
        }

        @Override
        public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
            return Optional.ofNullable(next);
        }

        @Override
        public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
            return Optional.ofNullable(next);
        }

        @Override
        public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String idempotencyKey) {
            return Optional.ofNullable(next);
        }

        @Override
        public List<ExternalActionCommandModel> claimDue(
                Instant now, Instant leaseUntil, String workerId, int limit) {
            return List.of(claimed);
        }

        @Override
        public boolean update(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
            this.next = next;
            return true;
        }
    }

    private static final class CountingItemStore implements AgentItemStore {

        private final AtomicInteger appendCount = new AtomicInteger();
        private final List<AgentItemModel> appended = new ArrayList<>();

        @Override
        public long appendItem(AgentItemModel item) {
            appendCount.incrementAndGet();
            appended.add(item);
            return appended.size();
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return List.of();
        }
    }

    private static final class ThrowingItemStore implements AgentItemStore {

        @Override
        public long appendItem(AgentItemModel item) {
            throw new IllegalStateException("item insert failed");
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return List.of();
        }
    }

    private static final class CountingTurnStore implements AgentTurnStore {

        private final AtomicInteger findCount = new AtomicInteger();
        private final AtomicInteger updateCount = new AtomicInteger();
        private final AgentTurnModel current;
        private AgentTurnModel updated;

        private CountingTurnStore() {
            this(null);
        }

        private CountingTurnStore(AgentTurnModel current) {
            this.current = current;
        }

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            findCount.incrementAndGet();
            return Optional.ofNullable(current);
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return Optional.empty();
        }

        @Override
        public void createTurn(AgentTurnModel turn) {
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel turn) {
            updateCount.incrementAndGet();
            updated = turn;
            return true;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return List.of();
        }
    }

    private static final class CountingWorkflowRunStore implements AgentWorkflowRunStore {

        private final AtomicInteger findCount = new AtomicInteger();
        private final AtomicInteger updateCount = new AtomicInteger();
        private final AgentWorkflowRunModel current;
        private AgentWorkflowRunModel updated;

        private CountingWorkflowRunStore() {
            this(null);
        }

        private CountingWorkflowRunStore(AgentWorkflowRunModel current) {
            this.current = current;
        }

        @Override
        public void create(AgentWorkflowRunModel run) {
        }

        @Override
        public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
            findCount.incrementAndGet();
            return Optional.ofNullable(current);
        }

        @Override
        public void update(AgentWorkflowRunModel run) {
            updateCount.incrementAndGet();
            updated = run;
        }
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private final Runnable rollbackAction;
        private int rollbackCount;

        private RecordingTransactionManager(Runnable rollbackAction) {
            this.rollbackAction = rollbackAction;
        }

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbackCount++;
            rollbackAction.run();
        }
    }
}
