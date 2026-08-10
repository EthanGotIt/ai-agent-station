package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.RouteDecisionModel;
import cn.ethan.core.agent.support.CancellationToken;
import cn.ethan.core.agent.port.RouteDecisionProvider;
import cn.ethan.core.order.service.OrderRequestAnalysisService;
import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.workflow.engine.GraphExecutor;
import cn.ethan.core.workflow.model.WorkflowContextModel;
import cn.ethan.core.workflow.model.WorkflowDescriptorModel;
import cn.ethan.core.workflow.model.WorkflowResultModel;
import cn.ethan.core.workflow.port.WorkflowExecutor;
import cn.ethan.core.workflow.order.OrderInquiryWorkflow;
import cn.ethan.core.workflow.after_sales.AfterSalesRefundWorkflow;
import cn.ethan.core.workflow.service.WorkflowRegistryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Agent 路由服务测试：验证规则优先、模型决策和非法结果降级边界。
 *
 * @author ethan
 * @date 2026-08-05
 */
class AgentRouterServiceTest {

    @Test
    void clockRuleDoesNotCallModel() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AgentRouterService router = router((request, userId, token) -> {
            modelCalled.set(true);
            return RouteDecisionModel.clarify("UNEXPECTED", List.of());
        });

        RouteDecisionModel decision = router.route(
                request("现在几点"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.ATOMIC, decision.routeType());
        assertEquals(AgentRouterService.CLOCK_EXECUTOR_ID, decision.executorId());
        assertFalse(modelCalled.get());
    }

    @Test
    void orderRuleDoesNotCallModel() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AgentRouterService router = router((request, userId, token) -> {
            modelCalled.set(true);
            return RouteDecisionModel.clarify("UNEXPECTED", List.of());
        });

        RouteDecisionModel decision = router.route(
                request("查询订单 ORDER-001"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.WORKFLOW, decision.routeType());
        assertEquals(OrderInquiryWorkflow.ID, decision.executorId());
        assertEquals(OrderInquiryWorkflow.DOMAIN_ID, decision.domainId());
        assertEquals("QUERY", decision.operation());
        assertFalse(modelCalled.get());
    }

    @Test
    void validModelDecisionIsPreserved() {
        AgentRouterService router = router((request, userId, token) ->
                new RouteDecisionModel(
                        RouteTypeEnum.REACT,
                        AgentRouterService.REACT_EXECUTOR_ID,
                        "open-readonly-question",
                        List.of(),
                        "MODEL_REACT"
                )
        );

        RouteDecisionModel decision = router.route(
                request("分析今天的行业动态"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.REACT, decision.routeType());
        assertEquals(AgentRouterService.REACT_EXECUTOR_ID, decision.executorId());
    }

    @Test
    void crossToolOrderAnalysisUsesReactWithoutCallingModelRouter() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AgentRouterService router = router((request, userId, token) -> {
            modelCalled.set(true);
            return RouteDecisionModel.clarify("UNEXPECTED", List.of());
        });

        RouteDecisionModel decision = router.route(
                request("请比较订单 ORDER-001 的商品和金额并综合分析"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.REACT, decision.routeType());
        assertEquals(AgentRouterService.REACT_EXECUTOR_ID, decision.executorId());
        assertFalse(modelCalled.get());
    }

    @Test
    void explicitSessionPreferenceSaveUsesReactWithoutCallingModelRouter() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AgentRouterService router = router((request, userId, token) -> {
            modelCalled.set(true);
            return RouteDecisionModel.clarify("UNEXPECTED", List.of());
        });

        RouteDecisionModel decision = router.route(
                request("请调用 save_session_preference，将 response.language 保存为 en-US"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.REACT, decision.routeType());
        assertEquals(AgentRouterService.REACT_EXECUTOR_ID, decision.executorId());
        assertFalse(modelCalled.get());
    }

    @Test
    void refundApplyUsesAfterSalesWorkflowWithoutCallingModelRouter() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AgentRouterService router = router((request, userId, token) -> {
            modelCalled.set(true);
            return RouteDecisionModel.clarify("UNEXPECTED", List.of());
        });

        RouteDecisionModel decision = router.route(
                request("订单 ORDER-PAID-001 因质量问题申请退款"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.WORKFLOW, decision.routeType());
        assertEquals("after_sales", decision.domainId());
        assertEquals(AfterSalesRefundWorkflow.ID, decision.executorId());
        assertEquals("APPLY", decision.operation());
        assertFalse(modelCalled.get());
    }

    @Test
    void afterSalesStatusUsesQueryStatusWithoutCallingModelRouter() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AgentRouterService router = router((request, userId, token) -> {
            modelCalled.set(true);
            return RouteDecisionModel.clarify("UNEXPECTED", List.of());
        });

        RouteDecisionModel decision = router.route(
                request("查询订单 ORDER-PAID-001 的售后状态"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.WORKFLOW, decision.routeType());
        assertEquals(AfterSalesRefundWorkflow.ID, decision.executorId());
        assertEquals("QUERY_STATUS", decision.operation());
        assertFalse(modelCalled.get());
    }

    @Test
    void unsupportedExecutorFallsBackToClarify() {
        AgentRouterService router = router((request, userId, token) ->
                new RouteDecisionModel(
                        RouteTypeEnum.WORKFLOW,
                        "unknown-workflow",
                        "unknown",
                        List.of(),
                        "MODEL_UNKNOWN"
                )
        );

        RouteDecisionModel decision = router.route(
                request("处理一个未知任务"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.CLARIFY, decision.routeType());
        assertEquals("ROUTER_UNSUPPORTED_EXECUTOR", decision.reasonCode());
    }

    @Test
    void providerFailureFallsBackToClarify() {
        AgentRouterService router = router((request, userId, token) -> {
            throw new IllegalStateException("invalid structured output");
        });

        RouteDecisionModel decision = router.route(
                request("处理一个未知任务"),
                "user-1",
                new CancellationToken()
        );

        assertEquals(RouteTypeEnum.CLARIFY, decision.routeType());
        assertEquals("ROUTER_INVALID", decision.reasonCode());
    }

    private AgentRequestModel request(String message) {
        return new AgentRequestModel("request-1", "session-1", message);
    }

    private AgentRouterService router(RouteDecisionProvider provider) {
        OrderRequestAnalysisService analysis = new OrderRequestAnalysisService();
        OrderInquiryWorkflow workflow = new OrderInquiryWorkflow(
                (orderId, userId) -> OrderLookupResultModel.notFound(),
                new GraphExecutor(),
                analysis,
                java.time.Clock.systemUTC(),
                java.time.Duration.ofHours(48),
                java.time.Duration.ofHours(48)
        );
        WorkflowExecutor afterSalesWorkflow = new WorkflowExecutor() {
            @Override
            public String workflowId() {
                return AfterSalesRefundWorkflow.ID;
            }

            @Override
            public WorkflowDescriptorModel descriptor() {
                return new WorkflowDescriptorModel(
                        "after_sales", AfterSalesRefundWorkflow.ID, "v1", List.of("APPLY", "QUERY_STATUS")
                );
            }

            @Override
            public WorkflowResultModel execute(WorkflowContextModel context) {
                throw new UnsupportedOperationException();
            }
        };
        return new AgentRouterService(
                provider,
                new WorkflowRegistryService(List.of(workflow, afterSalesWorkflow)),
                analysis
        );
    }
}
