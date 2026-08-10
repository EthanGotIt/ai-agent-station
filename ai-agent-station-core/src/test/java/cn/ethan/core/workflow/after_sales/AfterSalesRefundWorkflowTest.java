package cn.ethan.core.workflow.after_sales;

import cn.ethan.core.after_sales.enums.AfterSalesOperationEnum;
import cn.ethan.core.after_sales.enums.AfterSalesCaseStatusEnum;
import cn.ethan.core.after_sales.enums.AfterSalesHandlingModeEnum;
import cn.ethan.core.after_sales.enums.RefundReasonEnum;
import cn.ethan.core.after_sales.model.AfterSalesCaseModel;
import cn.ethan.core.after_sales.port.AfterSalesCaseGateway;
import cn.ethan.core.after_sales.model.RefundCommandModel;
import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.port.RefundCommandGateway;
import cn.ethan.core.after_sales.service.AfterSalesRequestAnalysisService;
import cn.ethan.core.after_sales.service.RefundEligibilityService;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.port.OrderGateway;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.workflow.engine.GraphExecutor;
import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;
import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;
import cn.ethan.core.workflow.model.WorkflowRunEventModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.port.WorkflowRunEventStore;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 售后退款 Workflow 测试：验证确认问题、幂等回答和重新校验边界。
 *
 * @author ethan
 * @date 2026-08-07
 */
class AfterSalesRefundWorkflowTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void confirmedRefundIsPersistedAndRepeatedAnswerDoesNotCreateSecondCommand() {
        MutableOrderGateway orders = new MutableOrderGateway(paidOrder());
        InMemoryRefundGateway refunds = new InMemoryRefundGateway();
        InMemoryRunStore runs = new InMemoryRunStore();
        AfterSalesRefundWorkflow workflow = workflow(orders, refunds, runs);

        WorkflowResultModel paused = workflow.execute(context("订单 ORDER-PAID-001 因质量问题退款"));

        assertEquals("WAITING_USER_INPUT", paused.status().name());
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");
        assertNotNull(run);
        assertEquals("after_sales", run.domainId());
        assertEquals("after-sales-refund", run.workflowId());

        WorkflowResultModel descriptionQuestion = workflow.answer(new WorkflowAnswerRequestModel(
                "resume-description", "session-1", run.runId(), paused.question().questionId(),
                run.checkpointId(), run.version(), Map.of("description", "商品存在明显质量问题，无法正常使用。")
        ), "user-1", new CancellationToken());
        WorkflowRunModel confirmRun = (WorkflowRunModel) descriptionQuestion.context().value("workflowRun");
        WorkflowAnswerRequestModel confirm = new WorkflowAnswerRequestModel(
                "resume-1", "session-1", confirmRun.runId(), descriptionQuestion.question().questionId(),
                confirmRun.checkpointId(), confirmRun.version(), Map.of("decision", "CONFIRM")
        );
        WorkflowResultModel completed = workflow.answer(confirm, "user-1", new CancellationToken());
        WorkflowResultModel repeated = workflow.answer(confirm, "user-1", new CancellationToken());

        assertEquals("COMPLETED", completed.status().name());
        assertEquals("COMPLETED", repeated.status().name());
        assertEquals(1, refunds.createdCount.get());
        assertEquals(WorkflowRunStatusEnum.COMPLETED, runs.current(run.runId()).status());
    }

    @Test
    void changedOrderStateRejectsAnswerBeforeCreatingRefund() {
        MutableOrderGateway orders = new MutableOrderGateway(paidOrder());
        InMemoryRefundGateway refunds = new InMemoryRefundGateway();
        InMemoryRunStore runs = new InMemoryRunStore();
        AfterSalesRefundWorkflow workflow = workflow(orders, refunds, runs);
        WorkflowResultModel paused = workflow.execute(context("订单 ORDER-PAID-001 退款"));
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");
        WorkflowResultModel confirmation = workflow.answer(new WorkflowAnswerRequestModel(
                "resume-reason", "session-1", run.runId(), paused.question().questionId(),
                run.checkpointId(), run.version(), Map.of("refundReason", "NOT_RECEIVED")
        ), "user-1", new CancellationToken());
        run = (WorkflowRunModel) confirmation.context().value("workflowRun");
        orders.order = new OrderSnapshotModel(
                "ORDER-PAID-001", "user-1", OrderStatusEnum.CANCELLED, null,
                CLOCK.instant(), null, null, null, new BigDecimal("99.00"), "CNY"
        );

        WorkflowResultModel rejected = workflow.answer(new WorkflowAnswerRequestModel(
                "resume-2", "session-1", run.runId(), confirmation.question().questionId(),
                run.checkpointId(), run.version(), Map.of("decision", "CONFIRM")
        ), "user-1", new CancellationToken());

        assertEquals("COMPLETED", rejected.status().name());
        assertEquals(WorkflowRunStatusEnum.REJECTED, runs.current(run.runId()).status());
        assertEquals(0, refunds.createdCount.get());
    }

    @Test
    void memorySuggestionIsPersistedButNeverBecomesWorkflowParameter() {
        MutableOrderGateway orders = new MutableOrderGateway(paidOrder());
        InMemoryRefundGateway refunds = new InMemoryRefundGateway();
        InMemoryRunStore runs = new InMemoryRunStore();
        AfterSalesRefundWorkflow workflow = workflow(orders, refunds, runs);
        AgentMemoryEntryModel suggestion = new AgentMemoryEntryModel(
                "memory-1", null, "user-1", "session-1", AgentMemoryCategoryEnum.TASK_CONTEXT,
                "order.id", "ORDER-PAID-001", AgentMemoryOriginEnum.AUTO, 0.95, 0L,
                false, CLOCK.instant().plusSeconds(3_600), CLOCK.instant(), CLOCK.instant()
        );
        WorkflowContextModel context = context("我要退款").with(
                "memorySuggestions", Map.of("order.id", suggestion)
        );

        WorkflowResultModel paused = workflow.execute(context);
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");

        assertEquals("ORDER-PAID-001", paused.question().fields().get(0).suggestion().value());
        assertEquals("memory-1", run.question().fields().get(0).suggestion().memoryEntryId());
        assertFalse(run.state().containsKey("orderId"));
        assertEquals(WorkflowRunStatusEnum.WAITING_USER_INPUT, run.status());
    }

    @Test
    void shippedOrderCreatesManualReviewCaseWithoutRefundCommand() {
        MutableOrderGateway orders = new MutableOrderGateway(new OrderSnapshotModel(
                "ORDER-SHIPPED-001", "user-1", OrderStatusEnum.SHIPPED, null,
                CLOCK.instant(), null, null, null, new BigDecimal("99.00"), "CNY"
        ));
        InMemoryRefundGateway refunds = new InMemoryRefundGateway();
        InMemoryRunStore runs = new InMemoryRunStore();
        InMemoryCaseGateway cases = new InMemoryCaseGateway();
        AfterSalesRefundWorkflow workflow = workflow(orders, refunds, runs, cases);

        WorkflowResultModel paused = workflow.execute(context("订单 ORDER-SHIPPED-001 没收到，申请退款"));
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");
        WorkflowResultModel completed = workflow.answer(new WorkflowAnswerRequestModel(
                "manual-confirm", "session-1", run.runId(), paused.question().questionId(),
                run.checkpointId(), run.version(), Map.of("decision", "CONFIRM")
        ), "user-1", new CancellationToken());

        assertEquals("COMPLETED", completed.status().name());
        assertEquals(0, refunds.createdCount.get());
        AfterSalesCaseModel created = cases.findByOrder("ORDER-SHIPPED-001", "user-1").orElseThrow();
        assertEquals(AfterSalesHandlingModeEnum.MANUAL_REVIEW, created.handlingMode());
        assertEquals(AfterSalesCaseStatusEnum.PENDING_REVIEW, created.status());
    }

    private AfterSalesRefundWorkflow workflow(
            OrderGateway orders,
            RefundCommandGateway refunds,
            WorkflowRunStore runs
    ) {
        return workflow(orders, refunds, runs, new NoOpCaseGateway());
    }

    private AfterSalesRefundWorkflow workflow(
            OrderGateway orders,
            RefundCommandGateway refunds,
            WorkflowRunStore runs,
            AfterSalesCaseGateway cases
    ) {
        return new AfterSalesRefundWorkflow(
                orders,
                refunds,
                cases,
                runs,
                event -> { },
                new AfterSalesRequestAnalysisService(new OrderRequestAnalysisService()),
                new RefundEligibilityService(),
                new GraphExecutor(),
                CLOCK
        );
    }

    private WorkflowContextModel context(String message) {
        return new WorkflowContextModel(
                new AgentRequestModel("request-1", "session-1", message),
                "user-1",
                new CancellationToken(),
                Map.of("operation", AfterSalesOperationEnum.APPLY.name())
        );
    }

    private OrderSnapshotModel paidOrder() {
        return new OrderSnapshotModel(
                "ORDER-PAID-001", "user-1", OrderStatusEnum.PAID, null,
                CLOCK.instant(), null, null, null, new BigDecimal("99.00"), "CNY"
        );
    }

    private static final class MutableOrderGateway implements OrderGateway {

        private OrderSnapshotModel order;

        private MutableOrderGateway(OrderSnapshotModel order) {
            this.order = order;
        }

        @Override
        public OrderLookupResultModel findOrder(String orderId, String userId) {
            if (!order.orderId().equals(orderId)) {
                return OrderLookupResultModel.notFound();
            }
            return userId.equals(order.userId())
                    ? OrderLookupResultModel.found(order)
                    : OrderLookupResultModel.denied();
        }
    }

    private static final class InMemoryRefundGateway implements RefundCommandGateway {

        private final Map<String, RefundCommandResultModel> refundsByRun = new HashMap<>();
        private final AtomicInteger createdCount = new AtomicInteger();

        @Override
        public RefundCommandResultModel create(RefundCommandModel command) {
            return refundsByRun.computeIfAbsent(command.workflowRunId(), ignored -> {
                createdCount.incrementAndGet();
                return new RefundCommandResultModel(
                        "REFUND-1", command.orderId(), command.userId(), "ACCEPTED",
                        command.amount(), command.currency(), command.createdAt()
                );
            });
        }

        @Override
        public Optional<RefundCommandResultModel> findByOrder(String orderId, String userId) {
            return refundsByRun.values().stream()
                    .filter(refund -> refund.orderId().equals(orderId) && refund.userId().equals(userId))
                    .findFirst();
        }
    }

    private static final class InMemoryRunStore implements WorkflowRunStore {

        private final Map<String, WorkflowRunModel> runs = new HashMap<>();

        @Override
        public void create(WorkflowRunModel run) {
            runs.put(run.runId(), run);
        }

        @Override
        public Optional<WorkflowRunModel> findOwned(String runId, String userId, String sessionId) {
            WorkflowRunModel run = runs.get(runId);
            if (run == null || !run.userId().equals(userId) || !run.sessionId().equals(sessionId)) {
                return Optional.empty();
            }
            return Optional.of(run);
        }

        @Override
        public boolean compareAndSet(WorkflowRunModel expected, WorkflowRunModel updated) {
            WorkflowRunModel current = runs.get(expected.runId());
            if (current == null || current.version() != expected.version()) {
                return false;
            }
            runs.put(updated.runId(), updated);
            return true;
        }

        private WorkflowRunModel current(String runId) {
            return runs.get(runId);
        }
    }

    private static final class NoOpCaseGateway implements AfterSalesCaseGateway {
        @Override
        public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
            return Optional.empty();
        }

        @Override
        public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
            return Optional.empty();
        }

        @Override
        public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
            return caseModel;
        }

        @Override
        public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
            return true;
        }
    }

    private static final class InMemoryCaseGateway implements AfterSalesCaseGateway {

        private final Map<String, AfterSalesCaseModel> casesByOrder = new HashMap<>();

        @Override
        public Optional<AfterSalesCaseModel> findByOrder(String orderId, String userId) {
            return Optional.ofNullable(casesByOrder.get(orderId))
                    .filter(caseModel -> caseModel.userId().equals(userId));
        }

        @Override
        public Optional<AfterSalesCaseModel> findByWorkflowRunId(String workflowRunId) {
            return casesByOrder.values().stream()
                    .filter(caseModel -> caseModel.workflowRunId().equals(workflowRunId)).findFirst();
        }

        @Override
        public AfterSalesCaseModel create(AfterSalesCaseModel caseModel) {
            return casesByOrder.putIfAbsent(caseModel.orderId(), caseModel) == null
                    ? caseModel : casesByOrder.get(caseModel.orderId());
        }

        @Override
        public boolean update(AfterSalesCaseModel expected, AfterSalesCaseModel updated) {
            return casesByOrder.replace(expected.orderId(), expected, updated);
        }
    }
}
