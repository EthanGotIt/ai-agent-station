package cn.ethan.infrastructure.agent.workflow.java;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.workflow.OrderWriteReservationStore;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 类型职责：验证 Java 退款/删除状态机的持久化版本、补参、授权复核和唯一命令边界。
 *
 * @author ethan
 * @date 2026-09-24
 */
class JavaRefundDeleteWorkflowEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void missingOrderAsksQuestionThenPersistsJavaCheckpoint() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID),
                order("ORDER-2", OrderStatusEnum.PAID)));

        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "REFUND", "reason", "不需要"));

        assertNotNull(started.questionCard());
        assertNull(started.checkpoint());
        assertEquals(AgentWorkflowOrchestrationVersionEnum.REFUND_JAVA_V1,
                fixture.runs.current.orchestrationVersion());
        AgentQuestionCardModel enqueued = started.questionCard().reserveAnswerTurn("answer-1").answerTurnEnqueued();
        fixture.questions.current = enqueued;
        AgentQuestionAnswerInput answer = new AgentQuestionAnswerInput(enqueued.questionId(),
                fixture.runs.current.runId(), AgentQuestionCardResumeTargetEnum.WORKFLOW,
                enqueued.version(), Map.of("orderId", "ORDER-2"), AgentQuestionCardAnswerActionEnum.SUBMIT);

        var resumed = fixture.engine.resume(fixture.thread, fixture.turn("answer-1", answer), Map.of());

        assertEquals("WAITING_USER_INPUT", resumed.resultStatus());
        assertNotNull(resumed.checkpoint());
        assertEquals("ORDER-2", resumed.checkpoint().orderId());
        assertEquals(1, fixture.runs.current.version());
    }

    @Test
    void approvalRechecksEligibilityAndCreatesOnlyOneCommand() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID)));
        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "urgent"));
        AgentWorkflowCheckpointModel checkpoint = started.checkpoint().approve(NOW);
        fixture.checkpoints.current = checkpoint;

        var result = fixture.engine.resume(fixture.thread,
                fixture.turn("decision-1", decision(fixture.runs.current, checkpoint,
                        AgentWorkflowDecisionEnum.APPROVE)), Map.of());

        assertEquals("APPROVED", result.resultStatus());
        assertEquals(ExternalActionTypeEnum.REFUND, result.command().type());
        assertEquals("order-service:" + fixture.runs.current.runId() + ":REFUND:ORDER-1",
                result.command().idempotencyKey());
        assertEquals(AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, fixture.runs.current.status());
        assertEquals(1, fixture.commands.created.size());

        var duplicate = fixture.engine.resume(fixture.thread,
                fixture.turn("decision-duplicate", decision(fixture.runs.current, checkpoint,
                        AgentWorkflowDecisionEnum.APPROVE)), Map.of());

        assertEquals(result.command().commandId(), duplicate.command().commandId());
        assertEquals(1, fixture.commands.created.size());
    }

    @Test
    void approvalWithNoLongerEligibleOrderFailsWithoutCreatingCommand() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID)));
        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "不需要"));
        AgentWorkflowCheckpointModel checkpoint = started.checkpoint().approve(NOW);
        fixture.checkpoints.current = checkpoint;
        fixture.currentOrder = order("ORDER-1", OrderStatusEnum.CANCELLED);

        var result = fixture.engine.resume(fixture.thread,
                fixture.turn("decision-1", decision(fixture.runs.current, checkpoint,
                        AgentWorkflowDecisionEnum.APPROVE)), Map.of());

        assertEquals("FAILED", result.resultStatus());
        assertEquals(AgentWorkflowStatusEnum.FAILED, fixture.runs.current.status());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED, fixture.checkpoints.current.status());
        assertTrue(fixture.commands.created.isEmpty());
    }

    @Test
    void approvalWithOrderOwnershipChangedFailsWithoutCreatingCommand() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID)));
        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "不需要"));
        AgentWorkflowCheckpointModel checkpoint = started.checkpoint().approve(NOW);
        fixture.checkpoints.current = checkpoint;
        fixture.currentOrder = new OrderSnapshotModel("ORDER-1", "other-user", OrderStatusEnum.PAID,
                4, NOW, NOW.plusSeconds(3600), NOW.minusSeconds(3600), "PAID");

        var result = fixture.engine.resume(fixture.thread,
                fixture.turn("decision-1", decision(fixture.runs.current, checkpoint,
                        AgentWorkflowDecisionEnum.APPROVE)), Map.of());

        assertEquals("FAILED", result.resultStatus());
        assertEquals(AgentWorkflowStatusEnum.FAILED, fixture.runs.current.status());
        assertEquals(AgentWorkflowCheckpointStatusEnum.SUPERSEDED, fixture.checkpoints.current.status());
        assertTrue(fixture.commands.created.isEmpty());
    }

    @Test
    void rejectionClosesWorkflowWithoutCreatingCommand() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID)));
        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "REFUND", "orderId", "ORDER-1", "reason", "不需要"));
        AgentWorkflowCheckpointModel checkpoint = started.checkpoint().reject(NOW);
        fixture.checkpoints.current = checkpoint;

        var result = fixture.engine.resume(fixture.thread,
                fixture.turn("decision-1", decision(fixture.runs.current, checkpoint,
                        AgentWorkflowDecisionEnum.REJECT)), Map.of());

        assertEquals("REJECTED", result.resultStatus());
        assertEquals(AgentWorkflowStatusEnum.REJECTED, fixture.runs.current.status());
        assertTrue(fixture.commands.created.isEmpty());
    }

    @Test
    void missingRefundReasonGetsIndependentQuestionBeforeCheckpoint() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID)));
        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "REFUND", "orderId", "ORDER-1"));
        assertNotNull(started.questionCard());
        assertNull(started.checkpoint());
        assertEquals("ORDER-1", fixture.reservations.orderId);

        AgentQuestionCardModel enqueued = started.questionCard().reserveAnswerTurn("answer-1")
                .answerTurnEnqueued();
        fixture.questions.current = enqueued;
        AgentQuestionAnswerInput answer = new AgentQuestionAnswerInput(enqueued.questionId(),
                fixture.runs.current.runId(), AgentQuestionCardResumeTargetEnum.WORKFLOW,
                enqueued.version(), Map.of("reason", "商品破损"), AgentQuestionCardAnswerActionEnum.SUBMIT);
        var resumed = fixture.engine.resume(fixture.thread, fixture.turn("answer-1", answer), Map.of());

        assertNotNull(resumed.checkpoint());
        assertEquals(AgentWorkflowOrchestrationVersionEnum.REFUND_JAVA_V1,
                fixture.runs.current.orchestrationVersion());
        assertEquals("REFUND", resumed.checkpoint().actionType());
    }

    @Test
    void deleteBindsScopeAndUsesDistinctVersion() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.CANCELLED)));
        var started = fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                Map.of("intent", "DELETE_ORDER", "orderId", "ORDER-1"));
        assertEquals(AgentWorkflowOrchestrationVersionEnum.DELETE_JAVA_V1,
                fixture.runs.current.orchestrationVersion());
        assertTrue(started.checkpoint().impactSummary().contains("物流轨迹"));
        AgentWorkflowCheckpointModel checkpoint = started.checkpoint().approve(NOW);
        fixture.checkpoints.current = checkpoint;
        var result = fixture.engine.resume(fixture.thread,
                fixture.turn("decision-1", decision(fixture.runs.current, checkpoint,
                        AgentWorkflowDecisionEnum.APPROVE)), Map.of());
        assertEquals(ExternalActionTypeEnum.DELETE_ORDER, result.command().type());
        assertTrue(result.command().payloadJson().contains("ORDER_AND_LOGISTICS"));
        assertEquals(1, fixture.commands.created.size());
    }

    @Test
    void activeOrderWriteFromAnotherRunRejectsNewWorkflow() {
        Fixture fixture = new Fixture(List.of(order("ORDER-1", OrderStatusEnum.PAID)));
        fixture.reservations.runId = "other-run";
        fixture.reservations.orderId = "ORDER-1";
        AgentThreadConflictException failure = assertThrows(AgentThreadConflictException.class,
                () -> fixture.engine.start(fixture.thread, fixture.turn("owner"), "ORDER_SERVICE",
                        Map.of("intent", "DELETE_ORDER", "orderId", "ORDER-1")));
        assertEquals("ORDER_WRITE_ACTIVE", failure.code());
        assertTrue(fixture.commands.created.isEmpty());
    }

    private AgentWorkflowDecisionInput decision(AgentWorkflowRunModel run,
                                                AgentWorkflowCheckpointModel checkpoint,
                                                AgentWorkflowDecisionEnum decision) {
        return new AgentWorkflowDecisionInput(run.runId(), checkpoint.checkpointId(),
                checkpoint.version() - 1, decision, checkpoint.factsFingerprint());
    }

    private AgentWorkflowRunModel newRun(AgentWorkflowRunModel current) { return current; }

    private AgentTurnModel ownerTurn(String id) {
        return new AgentTurnModel(id, "thread-1", "user-1", "request-" + id, "start",
                AgentTurnStatusEnum.ACTIVE, 0, null, null, NOW, NOW, null);
    }

    private OrderSnapshotModel order(String id, OrderStatusEnum status) {
        return new OrderSnapshotModel(id, "user-1", status, 4, NOW, NOW.plusSeconds(3600),
                NOW.minusSeconds(3600), "PAID");
    }

    private AgentTurnModel decisionTurn(String id, AgentWorkflowDecisionInput input) {
        return new AgentTurnModel(id, "thread-1", "user-1", "request-" + id, "decision",
                AgentTurnStatusEnum.ACTIVE, 0, input.runId(), null, NOW, NOW, null,
                null, 0, AgentTurnInputKindEnum.WORKFLOW_DECISION, null, null, input);
    }

    private AgentTurnModel questionTurn(String id, AgentQuestionAnswerInput input) {
        return new AgentTurnModel(id, "thread-1", "user-1", "request-" + id, "answer",
                AgentTurnStatusEnum.ACTIVE, 0, input.runId(), null, NOW, NOW, null,
                input, 0, AgentTurnInputKindEnum.QUESTION_ANSWER, null, null, null);
    }

    private final class Fixture {
        private final AgentThreadModel thread = new AgentThreadModel("thread-1", "user-1", "订单",
                AgentThreadStatusEnum.ACTIVE, "ORDER", "ORDER-1", 0, NOW, NOW);
        private final Runs runs = new Runs();
        private final Questions questions = new Questions();
        private final Checkpoints checkpoints = new Checkpoints();
        private final Commands commands = new Commands();
        private final Reservations reservations = new Reservations();
        private OrderSnapshotModel currentOrder;
        private final JavaRefundDeleteWorkflowEngine engine;

        private Fixture(List<OrderSnapshotModel> candidates) {
            this.currentOrder = candidates.isEmpty() ? null : candidates.get(0);
            OrderGateway orderGateway = new OrderGateway() {
                @Override
                public OrderLookupResultModel findOrder(String orderId, String userId) {
                    if (currentOrder != null && currentOrder.orderId().equals(orderId)) {
                        return OrderLookupResultModel.found(currentOrder);
                    }
                    return candidates.stream().filter(order -> order.orderId().equals(orderId)).findFirst()
                            .map(OrderLookupResultModel::found).orElseGet(OrderLookupResultModel::notFound);
                }

                @Override
                public OrderSearchResultModel searchOrders(OrderSearchCriteria criteria, String userId) {
                    return OrderSearchResultModel.success(candidates);
                }
            };
            engine = new JavaRefundDeleteWorkflowEngine(Clock.fixed(NOW, ZoneOffset.UTC), commands,
                    new ObjectMapper(), runs, orderGateway, null, null, null, null, null,
                    questions, checkpoints, null, reservations);
        }

        private AgentTurnModel turn(String id) { return ownerTurn(id); }
        private AgentTurnModel turn(String id, AgentQuestionAnswerInput input) { return questionTurn(id, input); }
        private AgentTurnModel turn(String id, AgentWorkflowDecisionInput input) { return decisionTurn(id, input); }
    }

    private static final class Runs implements AgentWorkflowRunStore {
        private AgentWorkflowRunModel current;
        @Override public void create(AgentWorkflowRunModel run) { current = run; }
        @Override public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
            return current != null && current.userId().equals(userId) && current.runId().equals(runId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<AgentWorkflowRunModel> findForUpdate(String userId, String runId) {
            return find(userId, runId);
        }
        @Override public Optional<AgentWorkflowRunModel> findBySource(String userId, String turnId,
                                                                      AgentWorkflowTypeEnum type) {
            return current != null && current.userId().equals(userId) && current.turnId().equals(turnId)
                    && current.workflowType() == type ? Optional.of(current) : Optional.empty();
        }
        @Override public void update(AgentWorkflowRunModel run) { current = run; }
    }

    private static final class Questions implements AgentQuestionCardStore {
        private AgentQuestionCardModel current;
        @Override public Optional<AgentQuestionCardModel> find(String userId, String questionId) {
            return current != null && current.questionId().equals(questionId) ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<AgentQuestionCardModel> findOpen(String userId, String threadId) {
            return current != null && current.status() == AgentQuestionCardStatusEnum.OPEN
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<AgentQuestionCardModel> findOpenByRun(String userId, String runId) {
            return current != null && current.status() == AgentQuestionCardStatusEnum.OPEN
                    && runId.equals(current.runId()) ? Optional.of(current) : Optional.empty();
        }
        @Override public void create(AgentQuestionCardModel question) { current = question; }
        @Override public OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion,
                                                         String answerTurnId) { return OptionalLong.empty(); }
        @Override public OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion,
                                                              String answerTurnId) { return OptionalLong.empty(); }
        @Override public boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion,
                                                    String answerTurnId) { return false; }
        @Override public boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                                                  String answerTurnId, AgentQuestionCardStatusEnum status,
                                                  Instant answeredAt) {
            if (current == null || current.version() != expectedVersion) return false;
            current = status == AgentQuestionCardStatusEnum.ANSWERED ? current.answer(answeredAt) : current.cancel(answeredAt);
            return true;
        }
    }

    private static final class Checkpoints implements AgentWorkflowCheckpointStore {
        private AgentWorkflowCheckpointModel current;
        @Override public Optional<AgentWorkflowCheckpointModel> find(String userId, String id) {
            return current != null && current.checkpointId().equals(id) ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<AgentWorkflowCheckpointModel> findForUpdate(String userId, String id) {
            return find(userId, id);
        }
        @Override public Optional<AgentWorkflowCheckpointModel> findOpen(String userId, String threadId) {
            return current != null && current.status() == AgentWorkflowCheckpointStatusEnum.OPEN
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<AgentWorkflowCheckpointModel> findOpenByRun(String userId, String runId) {
            return current != null && current.status() == AgentWorkflowCheckpointStatusEnum.OPEN
                    && current.runId().equals(runId) ? Optional.of(current) : Optional.empty();
        }
        @Override public void create(AgentWorkflowCheckpointModel checkpoint) { current = checkpoint; }
        @Override public boolean decide(String userId, String id, long expectedVersion,
                                        AgentWorkflowDecisionEnum decision, String fingerprint) { return false; }
        @Override public boolean supersede(String userId, String id, long expectedVersion) {
            if (current == null || !current.checkpointId().equals(id) || current.version() != expectedVersion) return false;
            current = current.supersede(NOW);
            return true;
        }
    }

    private static final class Commands implements ExternalActionCommandStore {
        private final List<ExternalActionCommandModel> created = new ArrayList<>();
        @Override public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel command) {
            created.add(command);
            return command;
        }
        @Override public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
            return created.stream().filter(value -> value.commandId().equals(commandId)).findFirst();
        }
        @Override public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
            return created.stream().filter(value -> value.runId().equals(runId)).findFirst();
        }
        @Override public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String key) {
            return created.stream().filter(value -> value.idempotencyKey().equals(key)).findFirst();
        }
        @Override public List<ExternalActionCommandModel> claimDue(Instant now, Instant leaseUntil,
                                                                   String workerId, int limit) { return List.of(); }
        @Override public boolean update(ExternalActionCommandModel expected, ExternalActionCommandModel next) {
            return false;
        }
    }

    private static final class Reservations implements OrderWriteReservationStore {
        private String orderId;
        private String runId;

        @Override public boolean reserve(String userId, String orderId, String runId) {
            if (this.runId != null && (!this.runId.equals(runId) || !this.orderId.equals(orderId))) return false;
            this.orderId = orderId;
            this.runId = runId;
            return true;
        }

        @Override public void release(String userId, String orderId, String runId) {
            if (runId.equals(this.runId) && orderId.equals(this.orderId)) {
                this.orderId = null;
                this.runId = null;
            }
        }
    }
}
