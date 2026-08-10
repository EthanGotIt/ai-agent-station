package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.model.ReActResultModel;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.workflow.engine.GraphExecutor;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.order.OrderInquiryWorkflow;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import cn.ethan.core.workflow.service.WorkflowRegistryService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 运行服务测试：验证 Workflow QuestionCard 的创建和恢复边界。
 *
 * @author ethan
 * @date 2026-08-09
 */
class AgentRuntimeServiceTest {

    @Test
    void workflowQuestionIsPersistedAndAnsweredExplicitly() {
        InMemoryRunStore runs = new InMemoryRunStore();
        AgentRuntimeService runtime = runtime(runs);

        AgentResponseModel question = runtime.handle(request("request-1", "查询我的订单"), "user-1");

        assertEquals(AgentStatusEnum.WAITING_USER_INPUT, question.status());
        assertNotNull(question.question());
        assertNotNull(question.workflowRun());
        WorkflowRunModel run = question.workflowRun();

        AgentResponseModel completed = runtime.answer(new WorkflowAnswerRequestModel(
                "request-2", "session-1", run.runId(), question.question().questionId(),
                run.checkpointId(), run.version(), Map.of("orderId", "ORDER-001")
        ), "user-1");

        assertEquals(AgentStatusEnum.COMPLETED, completed.status());
        assertEquals("order", completed.domainId());
        assertNotNull(completed.structuredResult());
        assertEquals("COMPLETED", runs.current(run.runId()).status().name());
    }

    @Test
    void diagnosisBranchUsesSameOrderInquiryWorkflow() {
        AgentRuntimeService runtime = runtime(new InMemoryRunStore());

        AgentResponseModel response = runtime.handle(
                request("request-1", "订单 ORDER-001 为什么还没发货"), "user-1"
        );

        assertEquals(AgentStatusEnum.COMPLETED, response.status());
        assertEquals(OrderInquiryWorkflow.ID, response.workflowId());
        assertEquals("DIAGNOSE", response.operation());
        assertTrue(response.content().contains("发货延迟"));
    }

    private AgentRequestModel request(String requestId, String message) {
        return new AgentRequestModel(requestId, "session-1", message);
    }

    private AgentRuntimeService runtime(InMemoryRunStore runs) {
        Clock clock = Clock.fixed(Instant.parse("2026-08-07T08:00:00Z"), ZoneOffset.UTC);
        OrderRequestAnalysisService analysis = new OrderRequestAnalysisService();
        OrderInquiryWorkflow workflow = new OrderInquiryWorkflow(
                (orderId, userId) -> OrderLookupResultModel.found(new OrderSnapshotModel(
                        orderId, userId, OrderStatusEnum.PAID, null,
                        clock.instant().minus(Duration.ofHours(72)), null, null, null
                )),
                new GraphExecutor(), analysis, clock,
                Duration.ofHours(48), Duration.ofHours(48), runs, event -> { }
        );
        WorkflowRegistryService workflows = new WorkflowRegistryService(List.of(workflow));
        return new AgentRuntimeService(
                new RequestLifecycleManager(Duration.ofMinutes(1), clock), queueManager(),
                new AgentRouterService(
                        (request, userId, token) -> cn.ethan.core.agent.model.RouteDecisionModel.clarify(
                                "UNEXPECTED", List.of()
                        ), workflows, analysis
                ),
                (request, userId, token) -> ReActResultModel.answer("react"),
                workflows, new OutputManager(clock), clock, null, 0, 1_000, runs
        );
    }

    private SessionExecutionQueueManager queueManager() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        return new SessionExecutionQueueManager(4, 16, Duration.ofMinutes(1), Runnable::run, scheduler);
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
