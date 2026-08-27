package cn.ethan.infrastructure.agent.workflow.langgraph;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
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
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        private final AgentThreadModel thread = new AgentThreadModel(
                "thread-1", "user-1", "售后", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
        private final AgentTurnModel owner = new AgentTurnModel(
                "owner-1", "thread-1", "user-1", "request-1", "我想退款",
                cn.ethan.core.agent.thread.AgentTurnStatusEnum.ACTIVE, 0, null, null,
                NOW, NOW, null);
        private final LangGraphAgentWorkflowEngine engine;

        private Fixture(List<OrderSnapshotModel> values) {
            orders = new FakeOrders(values);
            engine = new LangGraphAgentWorkflowEngine(
                    Clock.fixed(NOW, ZoneOffset.UTC), commands, new ObjectMapper(), runs, orders,
                    null, items, null, null, questions, checkpoints);
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
            return values.stream().filter(value -> value.orderId().equals(orderId))
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
