package cn.ethan.core.workflow.order;

import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.model.LogisticsEventModel;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.port.OrderGateway;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.workflow.engine.GraphExecutor;
import cn.ethan.core.workflow.exception.WorkflowRunConflictException;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 订单 V2 Workflow 测试：验证订单选择、物流轨迹、问题补充与终态回答幂等。
 *
 * @author ethan
 * @date 2026-08-10
 */
class OrderInquiryWorkflowTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void trackUsesRecentOrderQuestionAndResumesFromRealTimeLookup() {
        InMemoryRunStore runs = new InMemoryRunStore();
        OrderInquiryWorkflow workflow = workflow(runs);

        WorkflowResultModel paused = workflow.execute(context("我的物流到哪了"));
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");
        assertEquals("resolve_order", run.checkpointId());
        assertEquals("TRACK", run.operation());

        WorkflowAnswerRequestModel answer = answer(run, paused, "answer-1", Map.of("orderId", "ORDER-001"));
        WorkflowResultModel completed = workflow.answer(answer, "user-1", new CancellationToken());
        WorkflowResultModel repeated = workflow.answer(answer, "user-1", new CancellationToken());

        assertEquals("COMPLETED", completed.status().name());
        assertEquals("logistics_timeline", completed.structuredResult().cardType());
        assertEquals("COMPLETED", repeated.status().name());
        assertEquals("COMPLETED", runs.current(run.runId()).status().name());
        assertThrows(WorkflowRunConflictException.class, () -> workflow.answer(
                answer(run, paused, "answer-2", Map.of("orderId", "ORDER-002")), "user-1", new CancellationToken()
        ));
    }

    @Test
    void diagnoseCollectsIssueTypeAfterOrderWasAlreadyProvided() {
        InMemoryRunStore runs = new InMemoryRunStore();
        OrderInquiryWorkflow workflow = workflow(runs);

        WorkflowResultModel paused = workflow.execute(context("订单 ORDER-001 有异常"));
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");
        assertEquals("resolve_issue", run.checkpointId());

        WorkflowResultModel completed = workflow.answer(answer(
                run, paused, "answer-issue", Map.of("issueType", "LOGISTICS_STALLED")
        ), "user-1", new CancellationToken());

        assertEquals("COMPLETED", completed.status().name());
        assertEquals("order_diagnosis", completed.structuredResult().cardType());
        assertEquals("LOGISTICS_STALLED", completed.structuredResult().data().get("diagnosisType"));
    }

    @Test
    void diagnosesFiveIssueCategoriesAndHonorsTimeBoundaries() {
        assertDiagnosis(
                new OrderSnapshotModel(
                        "ORDER-PAID-DELAY", "user-1", OrderStatusEnum.PAID, null,
                        CLOCK.instant().minus(Duration.ofHours(48)), null, null, null,
                        new BigDecimal("99.00"), "CNY"
                ),
                "NOT_SHIPPED", List.of(), "SHIPMENT_DELAY"
        );
        assertDiagnosis(
                new OrderSnapshotModel(
                        "ORDER-DELIVERY-OVERDUE", "user-1", OrderStatusEnum.SHIPPED, null,
                        CLOCK.instant().minus(Duration.ofHours(96)), CLOCK.instant().minusSeconds(1),
                        CLOCK.instant().minus(Duration.ofHours(12)), "DELIVERING",
                        new BigDecimal("99.00"), "CNY"
                ),
                "DELIVERY_OVERDUE", List.of(), "DELIVERY_OVERDUE"
        );
        assertDiagnosis(
                new OrderSnapshotModel(
                        "ORDER-LOGISTICS-STALLED", "user-1", OrderStatusEnum.SHIPPED, null,
                        CLOCK.instant().minus(Duration.ofHours(96)), CLOCK.instant().plus(Duration.ofHours(24)),
                        CLOCK.instant().minus(Duration.ofHours(48)), "IN_TRANSIT",
                        new BigDecimal("99.00"), "CNY"
                ),
                "LOGISTICS_STALLED", List.of(new LogisticsEventModel(
                        "event-stalled", "ORDER-LOGISTICS-STALLED", "IN_TRANSIT", "上海",
                        "包裹停滞", CLOCK.instant().minus(Duration.ofHours(48))
                )), "LOGISTICS_STALLED"
        );
        assertDiagnosis(
                new OrderSnapshotModel(
                        "ORDER-DELIVERY-DISPUTE", "user-1", OrderStatusEnum.DELIVERED, 1,
                        CLOCK.instant().minus(Duration.ofDays(1)), null, CLOCK.instant(), "SIGNED",
                        new BigDecimal("99.00"), "CNY"
                ),
                "DELIVERED_NOT_RECEIVED", List.of(), "DELIVERY_DISPUTE"
        );
        assertDiagnosis(
                new OrderSnapshotModel(
                        "ORDER-OTHER", "user-1", OrderStatusEnum.SHIPPED, null,
                        CLOCK.instant().minus(Duration.ofHours(1)), null, null, null,
                        new BigDecimal("99.00"), "CNY"
                ),
                "OTHER", List.of(), "INSUFFICIENT_DATA"
        );
    }

    @Test
    void resumesPersistedQuestionAfterWorkflowIsRebuilt() {
        InMemoryRunStore runs = new InMemoryRunStore();
        WorkflowResultModel paused = workflow(runs).execute(context("我的物流到哪了"));
        WorkflowRunModel run = (WorkflowRunModel) paused.context().value("workflowRun");

        WorkflowResultModel completed = workflow(runs).answer(
                answer(run, paused, "answer-after-restart", Map.of("orderId", "ORDER-001")),
                "user-1", new CancellationToken()
        );

        assertEquals("COMPLETED", completed.status().name());
        assertEquals("logistics_timeline", completed.structuredResult().cardType());
    }

    private void assertDiagnosis(
            OrderSnapshotModel order,
            String issueType,
            List<LogisticsEventModel> trace,
            String expectedDiagnosis
    ) {
        InMemoryRunStore runs = new InMemoryRunStore();
        OrderInquiryWorkflow workflow = diagnosticWorkflow(runs, order, trace);
        WorkflowResultModel result = workflow.execute(context(
                "履约诊断",
                Map.of("operation", "DIAGNOSE", "orderId", order.orderId(), "issueType", issueType)
        ));

        assertEquals("COMPLETED", result.status().name());
        assertEquals("order_diagnosis", result.structuredResult().cardType());
        assertEquals(expectedDiagnosis, result.structuredResult().data().get("diagnosisType"));
    }

    private OrderInquiryWorkflow workflow(InMemoryRunStore runs) {
        OrderGateway orders = new OrderGateway() {
            @Override
            public OrderLookupResultModel findOrder(String orderId, String userId) {
                return "ORDER-001".equals(orderId) && "user-1".equals(userId)
                        ? OrderLookupResultModel.found(new OrderSnapshotModel(
                        orderId, userId, OrderStatusEnum.SHIPPED, null,
                        CLOCK.instant().minus(Duration.ofHours(96)), CLOCK.instant().plus(Duration.ofHours(24)),
                        CLOCK.instant().minus(Duration.ofHours(72)), "IN_TRANSIT"
                )) : OrderLookupResultModel.notFound();
            }

            @Override
            public List<RecentOrderModel> listRecentOrders(String userId, int limit) {
                return List.of(new RecentOrderModel("ORDER-001", OrderStatusEnum.SHIPPED, CLOCK.instant()));
            }
        };
        return new OrderInquiryWorkflow(
                orders,
                (orderId, userId) -> List.of(new LogisticsEventModel(
                        "event-1", orderId, "IN_TRANSIT", "上海", "包裹正在转运", CLOCK.instant().minus(Duration.ofHours(72))
                )),
                new GraphExecutor(), new OrderRequestAnalysisService(), CLOCK,
                Duration.ofHours(48), Duration.ofHours(48), runs, event -> { }
        );
    }

    private WorkflowContextModel context(String message) {
        return new WorkflowContextModel(
                new AgentRequestModel("request-1", "session-1", message), "user-1", new CancellationToken(), Map.of()
        );
    }

    private WorkflowContextModel context(String message, Map<String, Object> values) {
        return new WorkflowContextModel(
                new AgentRequestModel("request-1", "session-1", message), "user-1", new CancellationToken(), values
        );
    }

    private OrderInquiryWorkflow diagnosticWorkflow(
            InMemoryRunStore runs,
            OrderSnapshotModel order,
            List<LogisticsEventModel> trace
    ) {
        OrderGateway orders = new OrderGateway() {
            @Override
            public OrderLookupResultModel findOrder(String orderId, String userId) {
                return order.orderId().equals(orderId) && order.userId().equals(userId)
                        ? OrderLookupResultModel.found(order) : OrderLookupResultModel.notFound();
            }

            @Override
            public List<RecentOrderModel> listRecentOrders(String userId, int limit) {
                return List.of(new RecentOrderModel(order.orderId(), order.status(), order.createdAt()));
            }
        };
        return new OrderInquiryWorkflow(
                orders,
                (orderId, userId) -> trace,
                new GraphExecutor(), new OrderRequestAnalysisService(), CLOCK,
                Duration.ofHours(48), Duration.ofHours(48), runs, event -> { }
        );
    }

    private WorkflowAnswerRequestModel answer(
            WorkflowRunModel run, WorkflowResultModel paused, String requestId, Map<String, String> answers
    ) {
        return new WorkflowAnswerRequestModel(
                requestId, "session-1", run.runId(), paused.question().questionId(),
                run.checkpointId(), run.version(), answers
        );
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
            return run == null || !run.userId().equals(userId) || !run.sessionId().equals(sessionId)
                    ? Optional.empty() : Optional.of(run);
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
}
