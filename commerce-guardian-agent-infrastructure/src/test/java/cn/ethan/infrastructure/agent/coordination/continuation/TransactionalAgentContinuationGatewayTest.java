package cn.ethan.infrastructure.agent.coordination.continuation;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.coordination.AgentContinuationGateway;
import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.execution.AgentTurnQueue;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 Continuation 的统一幂等键、首事实持久化、队列触发和轮次上限。
 *
 * @author ethan
 * @date 2026-08-27
 */
class TransactionalAgentContinuationGatewayTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void admitsOneContinuationWithAllTriggerFactsAndEnqueuesAfterAdmission() {
        Fixture fixture = new Fixture(true, 3);
        AgentContinuationGateway.AdmissionResult result = fixture.gateway.admit(
                fixture.command(ExternalActionStatusEnum.SUCCEEDED), fixture.trigger(7));

        assertTrue(result.newlyAdmitted());
        assertNotNull(result.turn());
        assertEquals(2, result.items().size());
        AgentContinuationInput input = result.turn().continuationInput();
        assertEquals("parent-turn", input.rootTurnId());
        assertEquals("parent-turn", input.parentTurnId());
        assertEquals("run-1", input.triggerRunId());
        assertEquals("command-1", input.triggerCommandId());
        assertEquals("SUCCEEDED", input.triggerStatus());
        assertEquals(7L, input.triggerSequence());
        assertEquals(1, input.cycleNo());
        assertEquals(input.idempotencyKey(), result.turn().clientRequestId());
        assertEquals(1, fixture.queue.turns.size());
        assertEquals(2, fixture.items.values.size());
    }

    @Test
    void repeatedAdmissionUsesTheSameClientRequestIdWithoutCreatingAnotherTurn() {
        Fixture fixture = new Fixture(true, 3);
        ExternalActionCommandModel command = fixture.command(ExternalActionStatusEnum.SUCCEEDED);

        AgentContinuationGateway.AdmissionResult first = fixture.gateway.admit(command, fixture.trigger(7));
        AgentContinuationGateway.AdmissionResult second = fixture.gateway.admit(command, fixture.trigger(7));

        assertTrue(first.newlyAdmitted());
        assertFalse(second.newlyAdmitted());
        assertEquals(first.turn().turnId(), second.turn().turnId());
        assertEquals(1, fixture.turns.created.size());
        assertEquals(2, fixture.items.values.size());
        assertEquals(1, fixture.queue.turns.size());
    }

    @Test
    void reachesStopLimitWithoutCreatingContinuationTurn() {
        Fixture fixture = new Fixture(true, 3);
        fixture.turns.parent = new AgentTurnModel(
                "parent-turn", "thread-1", "user-1", "request-parent", "续跑",
                AgentTurnStatusEnum.COMPLETED, 0, null, null, NOW, NOW, NOW,
                null, 3L, cn.ethan.core.agent.thread.AgentTurnInputKindEnum.AGENT_CONTINUATION,
                null, new AgentContinuationInput("root-turn", "older-parent", "old-run", "old-command",
                "SUCCEEDED", 6L, 3));

        AgentContinuationGateway.AdmissionResult result = fixture.gateway.admit(
                fixture.command(ExternalActionStatusEnum.SUCCEEDED), fixture.trigger(8));

        assertTrue(result.stopLimit());
        assertEquals(4, result.cycleNo());
        assertEquals(2, result.items().size());
        assertEquals(0, fixture.turns.created.size());
        assertEquals(0, fixture.queue.turns.size());
        assertTrue(result.items().stream().anyMatch(item -> item.type() == AgentItemTypeEnum.AGENT_DECISION));
    }

    @Test
    void disabledAdmissionDoesNotWriteOrEnqueue() {
        Fixture fixture = new Fixture(false, 3);
        fixture.gateway = new TransactionalAgentContinuationGateway(
                fixture.turns, fixture.items, fixture.queue, Clock.fixed(NOW, ZoneOffset.UTC), false, 3);

        AgentContinuationGateway.AdmissionResult result = fixture.gateway.admit(
                fixture.command(ExternalActionStatusEnum.SUCCEEDED), fixture.trigger(7));

        assertFalse(result.newlyAdmitted());
        assertTrue(result.items().isEmpty());
        assertTrue(fixture.turns.created.isEmpty());
        assertTrue(fixture.items.values.isEmpty());
        assertTrue(fixture.queue.turns.isEmpty());
    }

    @Test
    void nonTerminalFailureDoesNotCreateContinuation() {
        Fixture fixture = new Fixture(true, 3);

        AgentContinuationGateway.AdmissionResult result = fixture.gateway.admit(
                fixture.command(ExternalActionStatusEnum.RETRY_WAIT), fixture.trigger(7));

        assertFalse(result.newlyAdmitted());
        assertFalse(result.stopLimit());
        assertTrue(result.items().isEmpty());
        assertTrue(fixture.turns.created.isEmpty());
        assertTrue(fixture.queue.turns.isEmpty());
    }

    @Test
    void concurrentAdmissionWithUniqueRequestConstraintCreatesOneTurn() throws Exception {
        Fixture fixture = new Fixture(true, 3);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AgentContinuationGateway.AdmissionResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return fixture.gateway.admit(fixture.command(ExternalActionStatusEnum.SUCCEEDED), fixture.trigger(7));
            });
            Future<AgentContinuationGateway.AdmissionResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return fixture.gateway.admit(fixture.command(ExternalActionStatusEnum.SUCCEEDED), fixture.trigger(7));
            });
            ready.await();
            start.countDown();

            AgentContinuationGateway.AdmissionResult firstResult = first.get();
            AgentContinuationGateway.AdmissionResult secondResult = second.get();

            assertEquals(1, fixture.turns.created.size());
            assertEquals(1, fixture.queue.turns.size());
            assertEquals(2, fixture.items.values.size());
            assertTrue(firstResult.newlyAdmitted() ^ secondResult.newlyAdmitted());
            assertEquals(firstResult.turn().turnId(), secondResult.turn().turnId());
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class Fixture {
        private final MemoryTurnStore turns = new MemoryTurnStore();
        private final MemoryItemStore items = new MemoryItemStore();
        private final RecordingQueue queue = new RecordingQueue();
        private TransactionalAgentContinuationGateway gateway;

        private Fixture(boolean enabled, int maxCycles) {
            gateway = new TransactionalAgentContinuationGateway(
                    turns, items, queue, Clock.fixed(NOW, ZoneOffset.UTC), enabled, maxCycles);
            turns.parent = new AgentTurnModel(
                    "parent-turn", "thread-1", "user-1", "request-parent", "退款",
                    AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION, 0, "run-1", null,
                    NOW.minusSeconds(10), NOW.minusSeconds(5), null);
        }

        private ExternalActionCommandModel command(ExternalActionStatusEnum status) {
            int attempts = status == ExternalActionStatusEnum.PROCESSING ? 1 : 2;
            Instant nextAttempt = status == ExternalActionStatusEnum.RETRY_WAIT
                    ? NOW.plusSeconds(30) : null;
            String leaseOwner = status == ExternalActionStatusEnum.PROCESSING ? "worker-1" : null;
            Instant leaseUntil = status == ExternalActionStatusEnum.PROCESSING ? NOW.plusSeconds(30) : null;
            return new ExternalActionCommandModel(
                    "command-1", "run-1", "thread-1", "parent-turn", "user-1",
                    ExternalActionTypeEnum.REFUND, "action-idem", "{\"orderId\":\"order-1\"}", status,
                    attempts, 3, nextAttempt, leaseOwner, leaseUntil,
                    null, null, NOW.minusSeconds(10), NOW,
                    status == ExternalActionStatusEnum.SUCCEEDED ? NOW : null, 1L, 1);
        }

        private AgentItemModel trigger(long sequence) {
            return new AgentItemModel("trigger-" + sequence, "thread-1", "parent-turn", sequence,
                    AgentItemTypeEnum.EXTERNAL_ACTION_STATUS, "{\"status\":\"SUCCEEDED\"}", NOW);
        }
    }

    private static final class MemoryTurnStore implements AgentTurnStore {
        private AgentTurnModel parent;
        private final List<AgentTurnModel> created = new ArrayList<>();

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            if (parent != null && parent.userId().equals(userId) && parent.turnId().equals(turnId)) {
                return Optional.of(parent);
            }
            return created.stream().filter(turn -> turn.userId().equals(userId) && turn.turnId().equals(turnId))
                    .findFirst();
        }

        @Override
        public synchronized Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return created.stream().filter(turn -> turn.userId().equals(userId)
                    && turn.clientRequestId().equals(clientRequestId)).findFirst();
        }

        @Override
        public synchronized Optional<AgentTurnModel> findTurnByRequestForUpdate(String userId, String clientRequestId) {
            return findTurnByRequest(userId, clientRequestId);
        }

        @Override
        public synchronized void createTurn(AgentTurnModel turn) {
            if (created.stream().anyMatch(existing -> existing.clientRequestId().equals(turn.clientRequestId()))) {
                throw new IllegalStateException("duplicate clientRequestId");
            }
            created.add(turn);
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
            return true;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return List.of();
        }
    }

    private static final class MemoryItemStore implements AgentItemStore {
        private final List<AgentItemModel> values = new ArrayList<>();

        @Override
        public long appendItem(AgentItemModel item) {
            values.add(item);
            return values.size();
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return List.of();
        }
    }

    private static final class RecordingQueue implements AgentTurnQueue {
        private final List<AgentTurnModel> turns = new ArrayList<>();

        @Override
        public void enqueuePersisted(AgentTurnModel turn) {
            turns.add(turn);
        }
    }
}
