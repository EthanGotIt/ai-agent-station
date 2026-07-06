package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.plan.ChecklistItem;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class RefundInformationGatheringPolicyTest {

    private final RefundInformationGatheringPolicy policy = new RefundInformationGatheringPolicy();

    @Test
    void shouldValidateReadyToEvaluatePlan() {
        RefundPlan plan = new RefundPlan(true, List.of(), List.of(
                new ChecklistItem("userId", "DONE"),
                new ChecklistItem("orderId", "DONE"),
                new ChecklistItem("orderStatus", "DONE"),
                new ChecklistItem("refundReason", "DONE")
        ));
        PlanningContext context = new PlanningContext("u1", "s1", "msg", "o1", "PAID", "DAMAGED", null, null, 0, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertTrue(result.ok());
    }

    @Test
    void shouldRejectUnknownAction() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("refund-step"), "REFUND", "orderId", null, null, null)
        ), List.of());
        PlanningContext context = new PlanningContext("u1", "s1", "msg", null, null, null, null, null, 0, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertFalse(result.ok());
        Assertions.assertEquals("ACTION_NOT_ALLOWED", result.errorType());
    }

    @Test
    void shouldRejectDisallowedTool() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("refund-order-step"), "TOOL_CALL", "orderId", "refund_order", Map.of("orderId", "o1"), null)
        ), List.of());
        PlanningContext context = new PlanningContext("u1", "s1", "msg", null, null, null, null, null, 0, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertFalse(result.ok());
        Assertions.assertEquals("TOOL_NOT_ALLOWED", result.errorType());
    }

    @Test
    void shouldRejectQueryOrderWithoutOrderId() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("query-no-order-id"), "TOOL_CALL", "orderStatus", "query_order", Map.of(), null)
        ), List.of());
        PlanningContext context = new PlanningContext("u1", "s1", "msg", null, null, null, null, null, 0, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertFalse(result.ok());
        Assertions.assertEquals("TOOL_INPUT_INVALID", result.errorType());
    }

    @Test
    void shouldRejectAskingForAlreadyPresentField() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("ask-order-id"), "ASK_USER", "orderId", null, null, "请提供订单号")
        ), List.of());
        PlanningContext context = new PlanningContext("u1", "s1", "msg", "o1", null, null, null, null, 0, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertFalse(result.ok());
        Assertions.assertEquals("NON_CONVERGENT_PLAN", result.errorType());
    }

    @Test
    void shouldAllowReAskingWhenRetrying() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("ask-order-id-retry"), "ASK_USER", "orderId", null, null, "请重新确认订单号")
        ), List.of());
        PlanningContext context = new PlanningContext("u1", "s1", "msg", "o1", null, null, null, "ORDER_NOT_FOUND", 1, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertTrue(result.ok());
    }

    @Test
    void shouldAllowValidQueryOrderStep() {
        RefundPlan plan = new RefundPlan(false, List.of(
                new PlannedStep(StepId.of("query-order"), "TOOL_CALL", "orderStatus", "query_order", Map.of("orderId", "o1"), null)
        ), List.of());
        PlanningContext context = new PlanningContext("u1", "s1", "msg", "o1", null, null, null, null, 0, 0, null, null);

        RefundInformationGatheringPolicy.ValidationResult result = policy.validate(plan, context);

        Assertions.assertTrue(result.ok());
    }
}
