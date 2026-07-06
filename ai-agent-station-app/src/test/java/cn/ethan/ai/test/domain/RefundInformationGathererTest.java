package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.plan.ChecklistItem;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.types.common.id.TurnId;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.RefundInformationGatherer;
import cn.ethan.ai.test.fixture.InMemoryAfterSalesRepository;
import cn.ethan.ai.test.fixture.StubAfterSalesToolPort;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RefundInformationGatherer} 单元测试。
 */
public class RefundInformationGathererTest {

    private InMemoryAfterSalesRepository repository;
    private RefundInformationGatheringPolicy policy;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAfterSalesRepository();
        repository.orders.put("ORDER-1", new AfterSalesOrderSnapshot("ORDER-1", "user-1", "PAID", null));
        policy = new RefundInformationGatheringPolicy();
    }

    @Test
    void shouldReachApprovalAfterOneRePlan() {
        RefundPlan firstPlan = queryOrderPlan("ORDER-MISSING");
        RefundPlan secondPlan = queryOrderPlan("ORDER-1");
        RefundPlanningAgent planningAgent = new ReplanningAgent(firstPlan, secondPlan);
        RefundInformationGatherer gatherer = new RefundInformationGatherer(
                new StubAfterSalesToolPort(repository), planningAgent, policy, null, null);

        AfterSalesAgentState result = gatherer.gather(initialState("ORDER-MISSING"),
                CaseId.of("case-1"), TurnId.of("turn-1"));

        Assertions.assertEquals(AfterSalesStage.PENDING_APPROVAL, result.stage());
        Assertions.assertEquals(1, result.count(AfterSalesAgentState.REPLAN_COUNT));
        Assertions.assertEquals("ORDER-1", result.text(AfterSalesAgentState.ORDER_ID));
        Assertions.assertNotNull(result.data().get(AfterSalesAgentState.CHECKLIST));
        Assertions.assertFalse(((java.util.List<?>) result.data().get(AfterSalesAgentState.CHECKLIST)).isEmpty());
    }

    @Test
    void shouldStopAndAskUserWhenPlanContainsAskUser() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("ask-order-id"), "ASK_USER", "orderId", null, null, "请提供订单号")
        ), List.of(
                new ChecklistItem("userId", "DONE"),
                new ChecklistItem("orderId", "PENDING"),
                new ChecklistItem("orderStatus", "PENDING"),
                new ChecklistItem("refundReason", "DONE")
        ));
        RefundInformationGatherer gatherer = new RefundInformationGatherer(
                new StubAfterSalesToolPort(repository), new FixedPlanAgent(plan), policy, null, null);

        AfterSalesAgentState result = gatherer.gather(initialState(null),
                CaseId.of("case-1"), TurnId.of("turn-1"));

        Assertions.assertEquals(AfterSalesStage.INTAKE, result.stage());
        Assertions.assertTrue(result.flag(AfterSalesAgentState.NEED_USER_INPUT));
        Assertions.assertEquals("请提供订单号", result.text(AfterSalesAgentState.DECISION_REASON));
        Assertions.assertEquals(0, result.count(AfterSalesAgentState.REPLAN_COUNT));
    }

    @Test
    void shouldRejectWhenRePlanAttemptsExceedMax() {
        RefundPlan plan = queryOrderPlan("ORDER-MISSING");
        RefundInformationGatherer gatherer = new RefundInformationGatherer(
                new StubAfterSalesToolPort(repository), new FixedPlanAgent(plan), policy, null, null);

        AfterSalesAgentState result = gatherer.gather(initialState("ORDER-MISSING"),
                CaseId.of("case-1"), TurnId.of("turn-1"));

        Assertions.assertEquals(AfterSalesStage.REJECTED, result.stage());
        Assertions.assertEquals("REPLAN_BUDGET_EXHAUSTED", result.text(AfterSalesAgentState.TERMINAL_REASON));
        Assertions.assertEquals(4, result.count(AfterSalesAgentState.REPLAN_COUNT));
    }

    @Test
    void shouldCarryLastErrorIntoPlanningContext() {
        RefundPlan firstPlan = queryOrderPlan("ORDER-MISSING");
        CapturingAgent agent = new CapturingAgent(firstPlan);
        RefundInformationGatherer gatherer = new RefundInformationGatherer(
                new StubAfterSalesToolPort(repository), agent, policy, null, null);

        gatherer.gather(initialState("ORDER-MISSING"),
                CaseId.of("case-1"), TurnId.of("turn-1"));

        Assertions.assertFalse(agent.contexts.isEmpty());
        PlanningContext replanContext = agent.contexts.get(agent.contexts.size() - 1);
        Assertions.assertTrue(replanContext.replanCount() > 0);
        Assertions.assertNotNull(replanContext.lastErrorMessage());
    }

    private AfterSalesAgentState initialState(String orderId) {
        Map<String, Object> data = new HashMap<>();
        data.put(AfterSalesAgentState.USER_ID, "user-1");
        data.put(AfterSalesAgentState.SESSION_ID, "session-1");
        data.put(AfterSalesAgentState.USER_MESSAGE, "退款");
        data.put(AfterSalesAgentState.ORDER_ID, orderId);
        data.put(AfterSalesAgentState.REFUND_REASON, "DAMAGED");
        data.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());
        return new AfterSalesAgentState(data);
    }

    private RefundPlan queryOrderPlan(String orderId) {
        return new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("query-order-" + orderId), "TOOL_CALL", "orderStatus", "query_order",
                        Map.of("orderId", orderId), null)
        ), List.of(
                new ChecklistItem("userId", "DONE"),
                new ChecklistItem("orderId", "DONE"),
                new ChecklistItem("orderStatus", "PENDING"),
                new ChecklistItem("refundReason", "DONE")
        ));
    }

    private static final class ReplanningAgent extends RefundPlanningAgent {
        private final RefundPlan first;
        private final RefundPlan second;
        private int calls;

        ReplanningAgent(RefundPlan first, RefundPlan second) {
            super(null);
            this.first = first;
            this.second = second;
        }

        @Override
        public RefundPlan plan(PlanningContext context) {
            calls++;
            return calls == 1 ? first : second;
        }
    }

    private static final class FixedPlanAgent extends RefundPlanningAgent {
        private final RefundPlan plan;

        FixedPlanAgent(RefundPlan plan) {
            super(null);
            this.plan = plan;
        }

        @Override
        public RefundPlan plan(PlanningContext context) {
            return plan;
        }
    }

    private static final class CapturingAgent extends RefundPlanningAgent {
        private final RefundPlan plan;
        private final List<PlanningContext> contexts = new java.util.ArrayList<>();

        CapturingAgent(RefundPlan plan) {
            super(null);
            this.plan = plan;
        }

        @Override
        public RefundPlan plan(PlanningContext context) {
            contexts.add(context);
            return plan;
        }
    }
}
