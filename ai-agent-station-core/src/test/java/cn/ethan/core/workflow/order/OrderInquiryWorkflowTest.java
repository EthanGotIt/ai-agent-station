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
