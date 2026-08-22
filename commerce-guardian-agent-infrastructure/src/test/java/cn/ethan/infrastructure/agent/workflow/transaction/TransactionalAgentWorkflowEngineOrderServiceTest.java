package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.LogisticsGateway;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证统一订单 Workflow 的多 Question 顺序、owner Turn 归属和最终命令幂等边界。
 *
 * @author ethan
 * @date 2026-08-23
 */
class TransactionalAgentWorkflowEngineOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void refundWorkflowFindsCandidatesCollectsReasonAndBindsActionToOwnerTurn() throws Exception {
        Fixture fixture = new Fixture();
        AgentWorkflowEngine.StartResult started = fixture.engine.start(
                fixture.thread, fixture.owner, "ORDER_SERVICE", Map.of("intent", "REFUND"));

        assertEquals("ORDER_SELECT", fixture.step(started.question()));
        assertEquals(1, fixture.mapper.readTree(started.question().fieldsJson()).path("stepNo").asInt());
        assertEquals(1, fixture.orders.searchCalls);
        assertEquals("owner-turn-1", started.question().turnId());
        assertEquals(AgentWorkflowStatusEnum.WAITING_USER_INPUT, fixture.runs.current.status());

        AgentTurnModel selectionAnswer = fixture.answer(started.question(), Map.of("orderId", "ORDER-002"));
        AgentWorkflowEngine.ResumeResult reason = fixture.engine.resume(
                fixture.thread, selectionAnswer, Map.of("orderId", "spoofed-transient-value"));

        assertNotNull(reason.question());
        assertEquals("REASON", fixture.step(reason.question()));
        assertEquals(2, reason.question().stepNo());
        assertEquals(AgentWorkflowStatusEnum.WAITING_USER_INPUT, fixture.runs.current.status());
        assertEquals(AgentTurnStatusEnum.WAITING_USER_INPUT, fixture.turns.owner.status());
        assertEquals("ORDER-002", fixture.runs.current.stateJson().contains("ORDER-002")
                ? "ORDER-002" : "missing");

        AgentTurnModel reasonAnswer = fixture.answer(reason.question(), Map.of("reason", "商品与描述不符"));
        AgentWorkflowEngine.ResumeResult confirmation = fixture.engine.resume(
                fixture.thread, reasonAnswer, Map.of());

        assertNotNull(confirmation.question());
        assertEquals("CONFIRM", fixture.step(confirmation.question()));
        JsonNode confirmationSchema = fixture.mapper.readTree(confirmation.question().fieldsJson());
        assertEquals("商品与描述不符", fixture.runs.current.stateJson().contains("商品与描述不符")
                ? "商品与描述不符" : "missing");
        assertEquals("ORDER_SERVICE", confirmationSchema.path("operation").asString());

        AgentTurnModel approvalAnswer = fixture.answer(confirmation.question(), Map.of("decision", "APPROVE"));
        AgentWorkflowEngine.ResumeResult approved = fixture.engine.resume(
                fixture.thread, approvalAnswer, Map.of("decision", "REJECT"));

        assertEquals("APPROVED", approved.resultStatus());
        assertNotNull(approved.command());
        assertEquals("owner-turn-1", approved.command().turnId());
        assertEquals("order-service:" + fixture.runs.current.runId() + ":REFUND:ORDER-002",
                approved.command().idempotencyKey());
        assertEquals(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, fixture.runs.current.status());
        assertEquals(AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION, fixture.turns.owner.status());
        assertEquals(1, fixture.commands.byKey.size());
        assertEquals(0, fixture.questions.openCount());

        assertThrows(RuntimeException.class,
                () -> fixture.engine.resume(fixture.thread, approvalAnswer, Map.of("decision", "APPROVE")));
    }

    @Test
    void missingIntentCreatesContextualQuestionBeforeSearching() throws Exception {
        Fixture fixture = new Fixture();
        AgentWorkflowEngine.StartResult started = fixture.engine.start(
                fixture.thread, fixture.owner, "ORDER_SERVICE", Map.of());

        assertEquals("INTENT", fixture.step(started.question()));
        assertEquals(0, fixture.orders.searchCalls);
        JsonNode schema = fixture.mapper.readTree(started.question().fieldsJson());
        assertEquals(List.of("REFUND", "EXPEDITE", "ORDER_HISTORY"), schema.path("fields").get(0).path("options")
                .valueStream().map(JsonNode::asString).toList());
    }

    @Test
    void orderHistoryWorkflowUsesSeparateActionQuestionAndCreatesHideCommand() throws Exception {
        Fixture fixture = new Fixture();
        AgentWorkflowEngine.StartResult started = fixture.engine.start(
                fixture.thread, fixture.owner, "ORDER_SERVICE", Map.of());

        AgentTurnModel intentAnswer = fixture.answer(started.question(), Map.of("intent", "ORDER_HISTORY"));
        AgentWorkflowEngine.ResumeResult historyAction = fixture.engine.resume(
                fixture.thread, intentAnswer, Map.of());
        assertEquals("HISTORY_ACTION", fixture.step(historyAction.question()));
        assertEquals(4, historyAction.question().stepNo());

        AgentTurnModel actionAnswer = fixture.answer(historyAction.question(), Map.of("historyAction", "HIDE_ORDER"));
        AgentWorkflowEngine.ResumeResult orderSelection = fixture.engine.resume(
                fixture.thread, actionAnswer, Map.of());
        assertEquals("ORDER_SELECT", fixture.step(orderSelection.question()));
        assertEquals(1, orderSelection.question().stepNo());

        AgentTurnModel selectionAnswer = fixture.answer(orderSelection.question(), Map.of("orderId", "ORDER-001"));
        AgentWorkflowEngine.ResumeResult confirmation = fixture.engine.resume(
                fixture.thread, selectionAnswer, Map.of());
        assertEquals("CONFIRM", fixture.step(confirmation.question()));

        AgentTurnModel approvalAnswer = fixture.answer(confirmation.question(), Map.of("decision", "APPROVE"));
        AgentWorkflowEngine.ResumeResult approved = fixture.engine.resume(
                fixture.thread, approvalAnswer, Map.of());
        assertEquals("HIDE_ORDER", approved.command().type().name());
        assertEquals("order-service:" + fixture.runs.current.runId() + ":HIDE_ORDER:ORDER-001",
                approved.command().idempotencyKey());
    }

    private static final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper();
        private final OrderSnapshotModel first = order("ORDER-001", "无线耳机", new BigDecimal("89.00"));
        private final OrderSnapshotModel second = order("ORDER-002", "键盘", new BigDecimal("199.00"));
        private final FakeOrders orders = new FakeOrders(first, second);
        private final FakeQuestions questions = new FakeQuestions();
        private final FakeRuns runs = new FakeRuns();
        private final FakeCommands commands = new FakeCommands();
        private final FakeItems items = new FakeItems();
        private final FakeTurns turns = new FakeTurns();
        private final AgentThreadModel thread = new AgentThreadModel(
                "thread-1", "user-1", "售后", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
        private final AgentTurnModel owner = new AgentTurnModel(
                "owner-turn-1", "thread-1", "user-1", "request-1", "我想退款",
                AgentTurnStatusEnum.WAITING_USER_INPUT, 0, null, null, NOW, NOW, null);
        private final TransactionalAgentWorkflowEngine engine;

        private Fixture() {
            turns.owner = owner;
            engine = new TransactionalAgentWorkflowEngine(
                    Clock.fixed(NOW, ZoneOffset.UTC), questions, commands, mapper, runs, orders,
                    (LogisticsGateway) (orderId, userId) -> List.of(new LogisticsEventModel(
                            "event-1", orderId, "运输中", "上海", "已到达分拨中心", NOW.minusSeconds(60))),
                    items, turns, new NoopEvents(), null);
        }

        private AgentTurnModel answer(AgentWorkflowQuestionModel question, Map<String, String> answers) {
            String answerTurnId = "answer-" + question.stepNo();
            questions.bindAnswer(answerTurnId, answers);
            AgentWorkflowAnswerInput input = new AgentWorkflowAnswerInput(
                    question.runId(), question.questionId(), question.checkpointId(),
                    questions.open.version(), answers);
            AgentTurnModel answer = new AgentTurnModel(
                    answerTurnId, "thread-1", "user-1", "request-" + answerTurnId,
                    "QuestionCard 回答", AgentTurnStatusEnum.ACTIVE, 0, question.runId(),
                    null, NOW, NOW, null, input);
            turns.answers.put(answerTurnId, answer);
            return answer;
        }

        private String step(AgentWorkflowQuestionModel question) throws Exception {
            return mapper.readTree(question.fieldsJson()).path("step").asString();
        }
    }

    private static OrderSnapshotModel order(String id, String item, BigDecimal amount) {
        return new OrderSnapshotModel(id, "user-1", OrderStatusEnum.PAID, null,
                NOW.minusSeconds(86_400), NOW.plusSeconds(86_400), NOW.minusSeconds(3_600),
                "运输中", amount, "CNY", item, null);
    }

    private static final class FakeOrders implements OrderGateway {
        private final List<OrderSnapshotModel> values;
        private int searchCalls;

        private FakeOrders(OrderSnapshotModel... values) {
            this.values = List.of(values);
        }

        @Override
        public OrderLookupResultModel findOrder(String orderId, String userId) {
            return values.stream().filter(value -> value.orderId().equals(orderId))
                    .findFirst().map(OrderLookupResultModel::found).orElseGet(OrderLookupResultModel::notFound);
        }

        @Override
        public OrderSearchResultModel searchOrders(OrderSearchCriteria criteria, String userId) {
            searchCalls++;
            return OrderSearchResultModel.success(values);
        }
    }

    private static final class FakeQuestions implements AgentWorkflowQuestionStore {
        private final Map<String, AgentWorkflowQuestionModel> all = new LinkedHashMap<>();
        private AgentWorkflowQuestionModel open;

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
            return Optional.ofNullable(open);
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
            return Optional.ofNullable(open).filter(value -> value.runId().equals(runId));
        }

        @Override
        public void saveQuestion(AgentWorkflowQuestionModel question) {
            if (open != null) {
                throw new IllegalStateException("fake store only allows one open question");
            }
            all.put(question.questionId(), question);
            open = question;
        }

        private void bindAnswer(String answerTurnId, Map<String, String> answers) {
            open = open.reserveAnswerTurn(answerTurnId).answerTurnEnqueued();
            all.put(open.questionId(), open);
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
                                       String answerTurnId, Instant answeredAt) {
            if (open == null || !open.questionId().equals(questionId) || open.version() != expectedVersion
                    || !answerTurnId.equals(open.answerTurnId())) {
                return false;
            }
            open = new AgentWorkflowQuestionModel(
                    open.runId(), open.threadId(), open.turnId(), open.userId(), open.questionId(),
                    open.checkpointId(), open.stepNo(), expectedVersion + 1, open.title(), open.prompt(),
                    open.fieldsJson(), AgentWorkflowQuestionStatusEnum.ANSWERED, open.createdAt(), answeredAt,
                    answerTurnId, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED,
                    open.answerFields());
            all.put(questionId, open);
            open = null;
            return true;
        }

        private int openCount() {
            return open == null ? 0 : 1;
        }
    }

    private static final class FakeRuns implements AgentWorkflowRunStore {
        private final Map<String, AgentWorkflowRunModel> values = new LinkedHashMap<>();
        private AgentWorkflowRunModel current;

        @Override
        public void create(AgentWorkflowRunModel run) {
            values.put(run.runId(), run);
            current = run;
        }

        @Override
        public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
            return Optional.ofNullable(values.get(runId));
        }

        @Override
        public void update(AgentWorkflowRunModel run) {
            current = run;
            values.put(run.runId(), run);
        }
    }

    private static final class FakeCommands implements ExternalActionCommandStore {
        private final Map<String, ExternalActionCommandModel> byKey = new LinkedHashMap<>();

        @Override
        public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command) {
            return byKey.computeIfAbsent(command.idempotencyKey(), ignored -> command);
        }

        @Override
        public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
            return byKey.values().stream().filter(value -> value.commandId().equals(commandId)).findFirst();
        }

        @Override
        public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
            return byKey.values().stream().filter(value -> value.runId().equals(runId)).findFirst();
        }

        @Override
        public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String key) {
            return Optional.ofNullable(byKey.get(key));
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
            values.add(item);
            return values.size();
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return List.copyOf(values);
        }
    }

    private static final class FakeTurns implements AgentTurnStore {
        private AgentTurnModel owner;
        private final Map<String, AgentTurnModel> answers = new LinkedHashMap<>();

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            if (owner != null && owner.turnId().equals(turnId)) {
                return Optional.of(owner);
            }
            return Optional.ofNullable(answers.get(turnId));
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return Optional.empty();
        }

        @Override
        public void createTurn(AgentTurnModel turn) {
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
            if (owner != null && owner.turnId().equals(expected.turnId()) && owner.version() == expected.version()) {
                owner = next;
                return true;
            }
            return false;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return List.of();
        }
    }

    private static final class NoopEvents implements AgentThreadEventGateway {
        @Override
        public void publish(AgentThreadEvent event) {
        }
    }
}
