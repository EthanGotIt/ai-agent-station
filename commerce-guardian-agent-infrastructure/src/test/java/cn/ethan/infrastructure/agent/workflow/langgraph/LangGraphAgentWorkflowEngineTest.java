package cn.ethan.infrastructure.agent.workflow.langgraph;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 LangGraph 订单引擎的 QuestionCard/Checkpoint 中断、恢复和外部命令边界。
 *
 * @author ethan
 * @date 2026-08-27
 */
class LangGraphAgentWorkflowEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void explicitExpediteOrderUsesPilotOrchestrationVersionWhenEnabled() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE")), false, true);

        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));

        assertEquals(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V1,
                fixture.runs.current.orchestrationVersion());
        assertNotNull(started.checkpoint());
        assertEquals(0, fixture.commands.values.size());
    }

    @Test
    void logisticsFactsUseExplicitFieldOrderForCrossProcessFingerprints() {
        LogisticsEventModel event = new LogisticsEventModel(
                "event-1", "ORDER-EXPEDITE", "已揽收", "杭州分拨中心", "包裹已揽收", NOW);

        assertEquals(List.of("eventId", "status", "location", "description", "occurredAt"),
                new ArrayList<>(LangGraphAgentWorkflowEngine.safeLogistics(event).keySet()));
    }

    @Test
    void pilotGraphPersistsEligibilityPhaseBeforeApprovalAndHandsOffOnlyAfterApproval() throws Exception {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE")), false, true);

        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));

        assertEquals(0, fixture.commands.values.size());
        assertTrue(fixture.runs.current.stateJson().contains("CONFIRMATION_READY"));
        assertTrue(fixture.runs.current.stateJson().contains("expedite-facts-v1:"));

        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());
        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("APPROVED", resumed.resultStatus());
        assertEquals(1, fixture.commands.values.size());
        assertEquals(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, fixture.runs.current.status());
        assertTrue(fixture.runs.current.stateJson().contains("HANDOFF_TO_WORKER"));
    }

    @Test
    void pilotApprovalAfterProcessRestartRebuildsFromBusinessFacts() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE")), false, true);
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));
        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());

        LangGraphAgentWorkflowEngine restarted = new LangGraphAgentWorkflowEngine(
                Clock.fixed(NOW, ZoneOffset.UTC), fixture.commands, new ObjectMapper(), fixture.runs,
                fixture.orders, null, fixture.items, null, null, fixture.questions, fixture.checkpoints, true);
        AgentWorkflowEngine.ResumeResult resumed = restarted.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("APPROVED", resumed.resultStatus());
        assertEquals(1, fixture.commands.values.size());
    }

    @Test
    void persistentPilotGraphRebuildsWhenTechnicalSnapshotIsLostAfterRestart() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE")), true, true);
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));
        assertTrue(fixture.saver.snapshotCount() > 0);
        fixture.saver.clearSnapshots();
        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());

        LangGraphAgentWorkflowEngine restarted = new LangGraphAgentWorkflowEngine(
                Clock.fixed(NOW, ZoneOffset.UTC), fixture.commands, new ObjectMapper(), fixture.runs,
                fixture.orders, null, fixture.items, null, null, fixture.questions, fixture.checkpoints,
                fixture.saver, true);
        AgentWorkflowEngine.ResumeResult resumed = restarted.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("APPROVED", resumed.resultStatus());
        assertEquals(1, fixture.commands.values.size());
        assertTrue(fixture.saver.snapshotCount() > 0);
    }

    @Test
    void explicitExpediteOrderUsesLegacyVersionWhenPilotIsDisabled() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE")));

        fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));

        assertEquals(AgentWorkflowOrchestrationVersionEnum.LEGACY_V1,
                fixture.runs.current.orchestrationVersion());
    }

    @Test
    void explicitOrderSelectionAcceptsCanonicalIdWithDifferentCase() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));

        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "order-1"));

        assertNull(started.questionCard());
        assertNotNull(started.checkpoint());
        assertEquals("ORDER-1", started.checkpoint().orderId());
    }

    @Test
    void missingOrderKeepsLegacyOrchestrationVersionEvenWhenPilotIsEnabled() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1"), fixtureOrder("ORDER-2")), false, true);

        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE"));

        assertEquals(AgentWorkflowOrchestrationVersionEnum.LEGACY_V1,
                fixture.runs.current.orchestrationVersion());
        assertNotNull(started.questionCard());
    }

    @Test
    void repeatedStartReturnsTheExistingRunInsteadOfCreatingAnotherCommandOrCheckpoint() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE")), false, true);

        AgentWorkflowEngine.StartResult first = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));
        AgentWorkflowEngine.StartResult repeated = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));

        assertEquals(first.runId(), repeated.runId());
        assertEquals(first.checkpoint().checkpointId(), repeated.checkpoint().checkpointId());
        assertEquals(1, fixture.checkpoints.values.size());
        assertEquals(0, fixture.commands.values.size());
    }

    @Test
    void repeatedSourceWithDifferentOrderIsRejected() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-EXPEDITE"), fixtureOrder("ORDER-OTHER")), false, true);

        fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-EXPEDITE"));

        assertThrows(AgentThreadConflictException.class, () -> fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-OTHER")));
    }

    @Test
    void repeatedCandidateSelectionReturnsTheExistingQuestionCard() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1"), fixtureOrder("ORDER-2")), false, true);

        AgentWorkflowEngine.StartResult first = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE"));
        AgentWorkflowEngine.StartResult repeated = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE"));

        assertEquals(first.runId(), repeated.runId());
        assertEquals(first.questionCard().questionId(), repeated.questionCard().questionId());
    }

    @Test
    void startsWithIndependentWorkflowCheckpointAndCreatesCommandOnlyAfterApproval() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "商品不符"));

        assertNull(started.questionCard());
        assertNotNull(started.checkpoint());
        assertEquals(AgentWorkflowCheckpointStatusEnum.OPEN, started.checkpoint().status());
        assertEquals(0, fixture.commands.values.size());

        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());
        AgentWorkflowDecisionInput input = new AgentWorkflowDecisionInput(
                started.runId(), started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());
        AgentTurnModel decisionTurn = new AgentTurnModel(
                "decision-1", "thread-1", "user-1", "decision-request", "Workflow Checkpoint 决策",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.ACTIVE, 0, started.runId(), null,
                 NOW, NOW, null, null, 0L, AgentTurnInputKindEnum.WORKFLOW_DECISION,
                null, null, input);

        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(fixture.thread, decisionTurn, Map.of());

        assertEquals("APPROVED", resumed.resultStatus());
        assertNotNull(resumed.command());
        assertEquals(ExternalActionStatusEnum.PENDING, resumed.command().status());
        assertEquals(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, fixture.runs.current.status());
        assertEquals(1, fixture.commands.values.size());
    }

    @Test
    void repeatedApprovalReturnsExistingCommandWithoutCreatingAnotherCommand() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-1"));
        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());

        AgentWorkflowEngine.ResumeResult first = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());
        AgentWorkflowEngine.ResumeResult repeated = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("APPROVED", first.resultStatus());
        assertEquals(first.command().commandId(), repeated.command().commandId());
        assertEquals(1, fixture.commands.values.size());
    }

    @Test
    void repeatedRejectionReturnsTheExistingTerminalResult() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "EXPEDITE", "orderId", "ORDER-1"));
        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.REJECT, "changed-by-reject-is-ignored");

        AgentWorkflowEngine.ResumeResult first = fixture.engine.resume(
                fixture.thread, decisionTurn(started, AgentWorkflowDecisionEnum.REJECT), Map.of());
        AgentWorkflowEngine.ResumeResult repeated = fixture.engine.resume(
                fixture.thread, decisionTurn(started, AgentWorkflowDecisionEnum.REJECT), Map.of());

        assertEquals("REJECTED", first.resultStatus());
        assertEquals("REJECTED", repeated.resultStatus());
        assertEquals(AgentWorkflowStatusEnum.REJECTED, fixture.runs.current.status());
    }

    @Test
    void directDeleteUsesAuthorizeCheckpointAndCreatesDeleteCommandAfterApproval() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-DELETE-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "DELETE_ORDER", "orderId", "ORDER-DELETE-1"));

        assertNull(started.questionCard());
        assertNotNull(started.checkpoint());
        assertEquals("DELETE_ORDER", started.checkpoint().actionType());
        assertTrue(started.checkpoint().impactSummary().contains("不可恢复"));
        assertEquals(0, fixture.commands.values.size());

        fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint());
        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("APPROVED", resumed.resultStatus());
        assertNotNull(resumed.command());
        assertEquals(cn.ethan.core.agent.action.ExternalActionTypeEnum.DELETE_ORDER,
                resumed.command().type());
        assertTrue(resumed.command().payloadJson().contains("ORDER-DELETE-1"));
        assertFalse(resumed.command().payloadJson().contains("visibility"));
    }

    @Test
    void createsWorkflowRunBeforePersistingGraphSnapshot() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")), true);

        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "商品不符"));

        assertNotNull(started.checkpoint());
        assertTrue(fixture.saver.allWritesObservedRun);
    }

    @Test
    void missingCandidateCreatesQuestionCardWithoutAuthorizationField() throws Exception {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1"), fixtureOrder("ORDER-2")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND"));

        assertNotNull(started.questionCard());
        assertNull(started.checkpoint());
        assertEquals("WORKFLOW", started.questionCard().resumeTarget().name());
        assertFalse(started.questionCard().fieldsJson().contains("AUTHORIZATION"));
        assertEquals(AgentWorkflowStatusEnum.WAITING_USER_INPUT, fixture.runs.current.status());
    }

    @Test
    void approvedCheckpointWithChangedFactsIsSupersededAndReopened() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "商品不符"));
        fixture.orders.replace(fixtureOrder("ORDER-1", OrderStatusEnum.SHIPPED));
        assertTrue(fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint()));

        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("FACTS_CHANGED", resumed.resultStatus());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED,
                fixture.checkpoints.values.get(started.checkpoint().checkpointId()).status());
        assertEquals(AgentWorkflowCheckpointStatusEnum.OPEN, resumed.checkpoint().status());
        assertEquals(AgentWorkflowStatusEnum.WAITING_USER_INPUT, fixture.runs.current.status());
        assertEquals(0, fixture.commands.values.size());
    }

    @Test
    void changedFactsThatInvalidateActionFailSafelyWithoutCreatingCommand() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "商品不符"));
        fixture.orders.replace(fixtureOrder("ORDER-1", OrderStatusEnum.CANCELLED));
        assertTrue(fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint()));

        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("FAILED", resumed.resultStatus());
        assertNull(resumed.checkpoint());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED,
                fixture.checkpoints.values.get(started.checkpoint().checkpointId()).status());
        assertEquals(AgentWorkflowStatusEnum.FAILED, fixture.runs.current.status());
        assertEquals(0, fixture.commands.values.size());
    }

    @Test
    void missingOrderAfterApprovalFailsAndSupersedesCheckpoint() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "商品不符"));
        fixture.orders.values.clear();
        assertTrue(fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.APPROVE, started.checkpoint().factsFingerprint()));

        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(
                fixture.thread, decisionTurn(started), Map.of());

        assertEquals("FAILED", resumed.resultStatus());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED,
                fixture.checkpoints.values.get(started.checkpoint().checkpointId()).status());
        assertEquals(AgentWorkflowStatusEnum.FAILED, fixture.runs.current.status());
        assertEquals(0, fixture.commands.values.size());
    }

    @Test
    void rejectionRemainsTerminalWhenFactsChange() {
        Fixture fixture = new Fixture(List.of(fixtureOrder("ORDER-1")));
        AgentWorkflowEngine.StartResult started = fixture.engine.start(fixture.thread, fixture.owner,
                "ORDER_SERVICE", Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "商品不符"));
        fixture.orders.replace(fixtureOrder("ORDER-1", OrderStatusEnum.SHIPPED));
        assertTrue(fixture.checkpoints.decide("user-1", started.checkpoint().checkpointId(), 0,
                AgentWorkflowDecisionEnum.REJECT, "facts-v2"));

        AgentWorkflowEngine.ResumeResult resumed = fixture.engine.resume(
                fixture.thread, decisionTurn(started, AgentWorkflowDecisionEnum.REJECT), Map.of());

        assertEquals("REJECTED", resumed.resultStatus());
        assertEquals(AgentWorkflowStatusEnum.REJECTED, fixture.runs.current.status());
        assertEquals(0, fixture.commands.values.size());
    }

    private AgentTurnModel decisionTurn(AgentWorkflowEngine.StartResult started) {
        return decisionTurn(started, AgentWorkflowDecisionEnum.APPROVE);
    }

    private AgentTurnModel decisionTurn(AgentWorkflowEngine.StartResult started,
                                        AgentWorkflowDecisionEnum decision) {
        AgentWorkflowDecisionInput input = new AgentWorkflowDecisionInput(
                started.runId(), started.checkpoint().checkpointId(), 0,
                decision, started.checkpoint().factsFingerprint());
        return new AgentTurnModel(
                "decision-" + started.runId(), "thread-1", "user-1", "decision-request-" + started.runId(),
                "Workflow Checkpoint 决策", cn.ethan.core.agent.thread.AgentTurnStatusEnum.ACTIVE, 0,
                started.runId(), null, NOW, NOW, null, null, 0L, AgentTurnInputKindEnum.WORKFLOW_DECISION,
                null, null, input);
    }

    private static OrderSnapshotModel fixtureOrder(String id) {
        return fixtureOrder(id, OrderStatusEnum.PAID);
    }

    private static OrderSnapshotModel fixtureOrder(String id, OrderStatusEnum status) {
        return new OrderSnapshotModel(id, "user-1", status, null,
                NOW.minusSeconds(3_600), NOW.plusSeconds(3_600), NOW.minusSeconds(60),
                "运输中", new BigDecimal("19.90"), "CNY", "商品", null);
    }

    private static final class Fixture {
        private final FakeRuns runs = new FakeRuns();
        private final FakeQuestions questions = new FakeQuestions();
        private final FakeCheckpoints checkpoints = new FakeCheckpoints();
        private final FakeCommands commands = new FakeCommands();
        private final FakeItems items = new FakeItems();
        private final FakeOrders orders;
        private final RecordingSaver saver;
        private final AgentThreadModel thread = new AgentThreadModel(
                "thread-1", "user-1", "售后", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
        private final AgentTurnModel owner = new AgentTurnModel(
                "owner-1", "thread-1", "user-1", "request-1", "我想退款",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.ACTIVE, 0, null, null,
                NOW, NOW, null);
        private final LangGraphAgentWorkflowEngine engine;

        private Fixture(List<OrderSnapshotModel> values) {
            this(values, false, false);
        }

        private Fixture(List<OrderSnapshotModel> values, boolean recordSnapshotOrder) {
            this(values, recordSnapshotOrder, false);
        }

        private Fixture(List<OrderSnapshotModel> values, boolean recordSnapshotOrder,
                        boolean expediteGraphEnabled) {
            orders = new FakeOrders(values);
            saver = recordSnapshotOrder ? new RecordingSaver(runs) : null;
            engine = saver == null
                    ? new LangGraphAgentWorkflowEngine(
                    Clock.fixed(NOW, ZoneOffset.UTC), commands, new ObjectMapper(), runs, orders,
                    null, items, null, null, questions, checkpoints, expediteGraphEnabled)
                    : new LangGraphAgentWorkflowEngine(
                    Clock.fixed(NOW, ZoneOffset.UTC), commands, new ObjectMapper(), runs, orders,
                    null, items, null, null, questions, checkpoints, saver, expediteGraphEnabled);
        }
    }

    private static final class RecordingSaver extends AbstractCheckpointSaver {
        private final FakeRuns runs;
        private boolean allWritesObservedRun = true;
        private final Map<String, LinkedList<Checkpoint>> snapshots = new LinkedHashMap<>();

        private RecordingSaver(FakeRuns runs) {
            this.runs = runs;
        }

        @Override
        protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
            return new LinkedList<>(snapshots.getOrDefault(config.threadId().orElse(""), new LinkedList<>()));
        }

        @Override
        protected void insertedCheckpoint(
                RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
            allWritesObservedRun &= runs.current != null;
            save(config, checkpoints);
        }

        @Override
        protected void updatedCheckpoint(
                RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) {
            allWritesObservedRun &= runs.current != null;
            save(config, checkpoints);
        }

        @Override
        protected BaseCheckpointSaver.Tag releaseCheckpoints(
                RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
            return new BaseCheckpointSaver.Tag(config.threadId().orElse(""), List.copyOf(checkpoints));
        }

        private void save(RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
            snapshots.put(config.threadId().orElse(""), new LinkedList<>(checkpoints));
        }

        private int snapshotCount() {
            return snapshots.values().stream().mapToInt(List::size).sum();
        }

        private void clearSnapshots() {
            snapshots.clear();
        }
    }

    private static final class FakeOrders implements OrderGateway {
        private final List<OrderSnapshotModel> values;

        private FakeOrders(List<OrderSnapshotModel> values) {
            this.values = new ArrayList<>(values);
        }

        private void replace(OrderSnapshotModel replacement) {
            for (int index = 0; index < values.size(); index++) {
                if (values.get(index).orderId().equals(replacement.orderId())) {
                    values.set(index, replacement);
                    return;
                }
            }
            values.add(replacement);
        }

        @Override
        public OrderLookupResultModel findOrder(String orderId, String userId) {
            return values.stream().filter(value -> value.orderId().equalsIgnoreCase(orderId))
                    .findFirst().map(OrderLookupResultModel::found)
                    .orElseGet(OrderLookupResultModel::notFound);
        }

        @Override
        public OrderSearchResultModel searchOrders(OrderSearchCriteria criteria, String userId) {
            return OrderSearchResultModel.success(values);
        }
    }

    private static final class FakeRuns implements AgentWorkflowRunStore {
        private AgentWorkflowRunModel current;

        @Override
        public void create(AgentWorkflowRunModel run) {
            current = run;
        }

        @Override
        public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
            return Optional.ofNullable(current).filter(value -> value.userId().equals(userId))
                    .filter(value -> value.runId().equals(runId));
        }

        @Override
        public Optional<AgentWorkflowRunModel> findBySource(
                String userId, String turnId, AgentWorkflowTypeEnum workflowType
        ) {
            return Optional.ofNullable(current)
                    .filter(value -> value.userId().equals(userId))
                    .filter(value -> value.turnId().equals(turnId))
                    .filter(value -> value.workflowType() == workflowType);
        }

        @Override
        public void update(AgentWorkflowRunModel run) {
            current = run;
        }
    }

    private static final class FakeQuestions implements AgentQuestionCardStore {
        private final Map<String, AgentQuestionCardModel> values = new LinkedHashMap<>();
        private String openId;

        @Override
        public Optional<AgentQuestionCardModel> find(String userId, String questionId) {
            return Optional.ofNullable(values.get(questionId)).filter(value -> value.userId().equals(userId));
        }

        @Override
        public Optional<AgentQuestionCardModel> findOpen(String userId, String threadId) {
            return Optional.ofNullable(openId).flatMap(id -> find(userId, id))
                    .filter(value -> value.threadId().equals(threadId));
        }

        @Override
        public Optional<AgentQuestionCardModel> findOpenByRun(String userId, String runId) {
            return Optional.ofNullable(openId).flatMap(id -> find(userId, id))
                    .filter(value -> runId.equals(value.runId()));
        }

        @Override
        public void create(AgentQuestionCardModel question) {
            values.put(question.questionId(), question);
            openId = question.questionId();
        }

        @Override
        public OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion,
                                               String answerTurnId) {
            return OptionalLong.empty();
        }

        @Override
        public OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion,
                                                   String answerTurnId) {
            return OptionalLong.empty();
        }

        @Override
        public boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion,
                                         String answerTurnId) {
            return false;
        }

        @Override
        public boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                                       String answerTurnId, AgentQuestionCardStatusEnum terminalStatus,
                                       Instant answeredAt) {
            return false;
        }
    }

    private static final class FakeCheckpoints implements AgentWorkflowCheckpointStore {
        private final Map<String, AgentWorkflowCheckpointModel> values = new LinkedHashMap<>();
        private String openId;

        @Override
        public Optional<AgentWorkflowCheckpointModel> find(String userId, String checkpointId) {
            return Optional.ofNullable(values.get(checkpointId)).filter(value -> value.userId().equals(userId));
        }

        @Override
        public Optional<AgentWorkflowCheckpointModel> findOpen(String userId, String threadId) {
            return Optional.ofNullable(openId).flatMap(id -> find(userId, id))
                    .filter(value -> value.threadId().equals(threadId));
        }

        @Override
        public Optional<AgentWorkflowCheckpointModel> findOpenByRun(String userId, String runId) {
            return Optional.ofNullable(openId).flatMap(id -> find(userId, id))
                    .filter(value -> runId.equals(value.runId()));
        }

        @Override
        public void create(AgentWorkflowCheckpointModel checkpoint) {
            values.put(checkpoint.checkpointId(), checkpoint);
            openId = checkpoint.checkpointId();
        }

        @Override
        public boolean decide(String userId, String checkpointId, long expectedVersion,
                               AgentWorkflowDecisionEnum decision, String currentFactsFingerprint) {
            AgentWorkflowCheckpointModel checkpoint = values.get(checkpointId);
            if (checkpoint == null || checkpoint.version() != expectedVersion
                    || (decision != AgentWorkflowDecisionEnum.REJECT
                    && !checkpoint.factsFingerprint().equals(currentFactsFingerprint))) {
                return false;
            }
            values.put(checkpointId, decision == AgentWorkflowDecisionEnum.APPROVE
                    ? checkpoint.approve(NOW) : checkpoint.reject(NOW));
            openId = null;
            return true;
        }

        @Override
        public boolean supersede(String userId, String checkpointId, long expectedVersion) {
            AgentWorkflowCheckpointModel checkpoint = values.get(checkpointId);
            if (checkpoint == null || checkpoint.version() != expectedVersion) {
                return false;
            }
            values.put(checkpointId, checkpoint.supersede(NOW));
            openId = null;
            return true;
        }
    }

    private static final class FakeCommands implements ExternalActionCommandStore {
        private final Map<String, ExternalActionCommandModel> values = new LinkedHashMap<>();

        @Override
        public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command) {
            return values.computeIfAbsent(command.idempotencyKey(), ignored -> command);
        }

        @Override
        public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
            return values.values().stream().filter(value -> value.commandId().equals(commandId)).findFirst();
        }

        @Override
        public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
            return values.values().stream().filter(value -> value.runId().equals(runId)).findFirst();
        }

        @Override
        public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String idempotencyKey) {
            return Optional.ofNullable(values.get(idempotencyKey));
        }

        @Override
        public List<ExternalActionCommandModel> claimDue(Instant now, Instant leaseUntil, String workerId, int limit) {
            return List.of();
        }

        @Override
        public boolean update(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
            return false;
        }
    }

    private static final class FakeItems implements AgentItemStore {
        private final List<AgentItemModel> values = new ArrayList<>();

        @Override
        public long appendItem(AgentItemModel item) {
            long sequence = values.size() + 1L;
            values.add(new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                    item.type(), item.payload(), item.createdAt()));
            return sequence;
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return values.stream().filter(value -> value.sequence() > afterSequence).limit(limit).toList();
        }
    }
}
