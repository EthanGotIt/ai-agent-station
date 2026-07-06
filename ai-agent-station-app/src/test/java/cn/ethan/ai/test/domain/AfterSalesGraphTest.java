package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.plan.ChecklistItem;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.policy.AfterSalesRefundEligibilityPolicy;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.SpringStateMachineAdapter;
import cn.ethan.ai.test.fixture.InMemoryAfterSalesRepository;
import cn.ethan.ai.test.fixture.InMemoryCheckpointRepository;
import cn.ethan.ai.test.fixture.StubAfterSalesToolPort;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring State Machine 路由测试。
 *
 * <p>验证 Plan-and-Execute 主链路下，SpringStateMachineAdapter 在各个边界条件中的路由结果。</p>
 */
public class AfterSalesGraphTest {

    private IAfterSalesStateMachine stateMachine;
    private InMemoryAfterSalesRepository repository;
    private InMemoryCheckpointRepository checkpointRepository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAfterSalesRepository();
        checkpointRepository = new InMemoryCheckpointRepository();
        repository.orders.put("order-1", new AfterSalesOrderSnapshot("order-1", "user-1", "PAID", null));
        stateMachine = new SpringStateMachineAdapter(
                new StubAfterSalesToolPort(repository),
                repository,
                new RefundPlanningAgent(null),
                new RefundInformationGatheringPolicy(),
                null,
                checkpointRepository);
    }

    @Test
    void shouldRouteEligiblePaidOrderToApprovalBoundary() {
        AfterSalesAgentState state = execute(eligibleInput(), "paid-order");

        Assertions.assertEquals(AfterSalesStage.PENDING_APPROVAL, state.stage());
        Assertions.assertEquals(Boolean.TRUE.toString(), state.text(AfterSalesAgentState.ELIGIBLE));
        Assertions.assertEquals("REFUND_REQUIRES_APPROVAL", state.text(AfterSalesAgentState.DECISION_REASON));
        Assertions.assertNotNull(state.data().get(AfterSalesAgentState.CHECKLIST));
        Assertions.assertFalse(((java.util.List<?>) state.data().get(AfterSalesAgentState.CHECKLIST)).isEmpty());
    }

    @Test
    void shouldAskForMissingOrderId() {
        Map<String, Object> input = eligibleInput();
        input.remove(AfterSalesAgentState.ORDER_ID);
        input.remove(AfterSalesAgentState.ORDER_STATUS);

        AfterSalesAgentState state = execute(input, "missing-order");

        Assertions.assertEquals(AfterSalesStage.INTAKE, state.stage());
        Assertions.assertTrue(state.flag(AfterSalesAgentState.NEED_USER_INPUT));
        Assertions.assertEquals("MISSING_REQUIRED_IDENTITY", state.text(AfterSalesAgentState.DECISION_REASON));
    }

    @Test
    void shouldRejectOrderOwnedByAnotherUser() {
        Map<String, Object> input = eligibleInput();
        input.put(AfterSalesAgentState.ORDER_OWNER_ID, "user-2");

        AfterSalesAgentState state = execute(input, "foreign-order");

        Assertions.assertEquals(AfterSalesStage.REJECTED, state.stage());
        Assertions.assertEquals("ORDER_NOT_OWNED", state.text(AfterSalesAgentState.TERMINAL_REASON));
    }

    @Test
    void shouldTreatAlreadyRefundedOrderAsIdempotentCompletion() {
        Map<String, Object> input = eligibleInput();
        input.put(AfterSalesAgentState.ORDER_STATUS, "REFUNDED");

        AfterSalesAgentState state = execute(input, "already-refunded");

        Assertions.assertEquals(AfterSalesStage.COMPLETED, state.stage());
        Assertions.assertEquals("ALREADY_REFUNDED", state.text(AfterSalesAgentState.TERMINAL_REASON));
    }

    @Test
    void shouldApplyDeliveredRefundWindowAndReasonDeterministically() {
        AfterSalesRefundEligibilityPolicy policy = new AfterSalesRefundEligibilityPolicy();

        AfterSalesRefundEligibilityPolicy.RefundDecision accepted = policy.evaluate(
                new AfterSalesRefundEligibilityPolicy.RefundRequest(
                        "user-1", "order-1", "user-1", "DELIVERED", "DAMAGED", 7
                )
        );
        AfterSalesRefundEligibilityPolicy.RefundDecision rejected = policy.evaluate(
                new AfterSalesRefundEligibilityPolicy.RefundRequest(
                        "user-1", "order-1", "user-1", "DELIVERED", "NO_LONGER_WANTED", 2
                )
        );

        Assertions.assertEquals(AfterSalesRefundEligibilityPolicy.RefundOutcome.ELIGIBLE, accepted.outcome());
        Assertions.assertEquals(AfterSalesRefundEligibilityPolicy.RefundOutcome.REJECTED, rejected.outcome());
        Assertions.assertEquals("DELIVERED_REFUND_RULE_NOT_MET", rejected.reason());
    }

    @Test
    void shouldRejectWhenRePlanBudgetExhausted() {
        stateMachine = new SpringStateMachineAdapter(
                new StubAfterSalesToolPort(repository),
                repository,
                new AlwaysQueryMissingAgent(),
                new RefundInformationGatheringPolicy(),
                null,
                checkpointRepository);

        Map<String, Object> input = eligibleInput();
        input.put(AfterSalesAgentState.ORDER_ID, "ORDER-MISSING");
        input.remove(AfterSalesAgentState.ORDER_STATUS);

        AfterSalesAgentState state = execute(input, "replan-budget-exhausted");

        Assertions.assertEquals(AfterSalesStage.REJECTED, state.stage());
        Assertions.assertEquals("REPLAN_BUDGET_EXHAUSTED", state.text(AfterSalesAgentState.TERMINAL_REASON));
        Assertions.assertEquals(4, state.count(AfterSalesAgentState.REPLAN_COUNT));
    }

    private AfterSalesAgentState execute(Map<String, Object> input, String threadId) {
        return stateMachine.execute(input, threadId);
    }

    private Map<String, Object> eligibleInput() {
        Map<String, Object> input = new HashMap<>();
        input.put(AfterSalesAgentState.USER_ID, "user-1");
        input.put(AfterSalesAgentState.SESSION_ID, "session-1");
        input.put(AfterSalesAgentState.ORDER_ID, "order-1");
        input.put(AfterSalesAgentState.ORDER_OWNER_ID, "user-1");
        input.put(AfterSalesAgentState.ORDER_STATUS, "PAID");
        input.put(AfterSalesAgentState.REFUND_REASON, "DAMAGED");
        return input;
    }

    private static final class AlwaysQueryMissingAgent extends RefundPlanningAgent {
        AlwaysQueryMissingAgent() {
            super(null);
        }

        @Override
        public RefundPlan plan(PlanningContext context) {
            return new RefundPlan(false, List.of(
                    new PlannedStep(StepId.of("query-order-missing"), "TOOL_CALL", "orderStatus", "query_order",
                            Map.of("orderId", "ORDER-MISSING"), null)
            ), List.of(
                    new ChecklistItem("userId", "DONE"),
                    new ChecklistItem("orderId", "DONE"),
                    new ChecklistItem("orderStatus", "PENDING"),
                    new ChecklistItem("refundReason", "DONE")
            ));
        }
    }
}
