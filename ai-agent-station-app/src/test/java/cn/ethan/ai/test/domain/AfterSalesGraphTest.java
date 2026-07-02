package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.service.AfterSalesGraphRuntime;
import cn.ethan.ai.domain.agent.service.RefundEligibilityPolicy;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

public class AfterSalesGraphTest {

    private AfterSalesGraphRuntime graphRuntime;

    @BeforeEach
    void setUp() throws Exception {
        graphRuntime = new AfterSalesGraphRuntime();
    }

    @Test
    void shouldRouteEligiblePaidOrderToApprovalBoundary() {
        AfterSalesAgentState state = execute(eligibleInput(), "paid-order");

        Assertions.assertEquals(AfterSalesStage.READY_FOR_APPROVAL, state.stage());
        Assertions.assertEquals(Boolean.TRUE, state.data().get(AfterSalesAgentState.ELIGIBLE));
        Assertions.assertEquals("REFUND_REQUIRES_APPROVAL", state.text(AfterSalesAgentState.DECISION_REASON));
    }

    @Test
    void shouldAskForMissingOrderId() {
        Map<String, Object> input = eligibleInput();
        input.remove(AfterSalesAgentState.ORDER_ID);

        AfterSalesAgentState state = execute(input, "missing-order");

        Assertions.assertEquals(AfterSalesStage.NEED_USER_INPUT, state.stage());
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
    void shouldRouteInvalidArgumentsToBoundedRepair() {
        Map<String, Object> input = Map.of(
                AfterSalesAgentState.ERROR_TYPE, "ARGUMENT_INVALID",
                AfterSalesAgentState.REPAIR_COUNT, 0
        );

        AfterSalesAgentState state = execute(input, "repair-tool-input");

        Assertions.assertEquals(AfterSalesStage.REPAIR_TOOL_INPUT, state.stage());
        Assertions.assertEquals("REPAIR_INVALID_ARGUMENTS", state.text(AfterSalesAgentState.DECISION_REASON));
    }

    @Test
    void shouldStopWhenRepairBudgetIsExhausted() {
        Map<String, Object> input = Map.of(
                AfterSalesAgentState.ERROR_TYPE, "ARGUMENT_INVALID",
                AfterSalesAgentState.REPAIR_COUNT, 2
        );

        AfterSalesAgentState state = execute(input, "repair-budget-exhausted");

        Assertions.assertEquals(AfterSalesStage.REJECTED, state.stage());
        Assertions.assertEquals("REPAIR_BUDGET_EXHAUSTED", state.text(AfterSalesAgentState.TERMINAL_REASON));
    }

    @Test
    void shouldRetryTransientFailureWithinBudget() {
        Map<String, Object> input = Map.of(
                AfterSalesAgentState.ERROR_TYPE, "TIMEOUT",
                AfterSalesAgentState.RETRY_COUNT, 1
        );

        AfterSalesAgentState state = execute(input, "retry-timeout");

        Assertions.assertEquals(AfterSalesStage.RETRY_TOOL, state.stage());
        Assertions.assertEquals("RETRY_TRANSIENT_FAILURE", state.text(AfterSalesAgentState.DECISION_REASON));
    }

    @Test
    void shouldStopForbiddenToolCallWithoutRetry() {
        AfterSalesAgentState state = execute(
                Map.of(AfterSalesAgentState.ERROR_TYPE, "FORBIDDEN"),
                "forbidden-tool"
        );

        Assertions.assertEquals(AfterSalesStage.REJECTED, state.stage());
        Assertions.assertEquals("TOOL_ACCESS_FORBIDDEN", state.text(AfterSalesAgentState.TERMINAL_REASON));
    }

    @Test
    void shouldExposeCheckpointHistoryForAThread() {
        String threadId = "checkpoint-history";
        execute(eligibleInput(), threadId);

        Assertions.assertFalse(graphRuntime.compiledGraph().getStateHistory(
                RunnableConfig.builder().threadId(threadId).build()
        ).isEmpty());
    }

    @Test
    void shouldApplyDeliveredRefundWindowAndReasonDeterministically() {
        RefundEligibilityPolicy policy = new RefundEligibilityPolicy();

        RefundEligibilityPolicy.RefundDecision accepted = policy.evaluate(
                new RefundEligibilityPolicy.RefundRequest(
                        "user-1", "order-1", "user-1", "DELIVERED", "DAMAGED", 7
                )
        );
        RefundEligibilityPolicy.RefundDecision rejected = policy.evaluate(
                new RefundEligibilityPolicy.RefundRequest(
                        "user-1", "order-1", "user-1", "DELIVERED", "NO_LONGER_WANTED", 2
                )
        );

        Assertions.assertEquals(RefundEligibilityPolicy.RefundOutcome.ELIGIBLE, accepted.outcome());
        Assertions.assertEquals(RefundEligibilityPolicy.RefundOutcome.REJECTED, rejected.outcome());
        Assertions.assertEquals("DELIVERED_REFUND_RULE_NOT_MET", rejected.reason());
    }

    private AfterSalesAgentState execute(Map<String, Object> input, String threadId) {
        return graphRuntime.execute(input, threadId);
    }

    private Map<String, Object> eligibleInput() {
        Map<String, Object> input = new HashMap<>();
        input.put(AfterSalesAgentState.RUN_ID, "run-1");
        input.put(AfterSalesAgentState.USER_ID, "user-1");
        input.put(AfterSalesAgentState.ORDER_ID, "order-1");
        input.put(AfterSalesAgentState.ORDER_OWNER_ID, "user-1");
        input.put(AfterSalesAgentState.ORDER_STATUS, "PAID");
        input.put(AfterSalesAgentState.REFUND_REASON, "DAMAGED");
        return input;
    }
}
