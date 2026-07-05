package cn.ethan.ai.infrastructure.adapter.statemachine.ssm;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.plan.PlanStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.policy.AfterSalesRefundEligibilityPolicy;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import org.springaicommunity.agent.tools.TodoWriteTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退款信息收集器（Phase 7.3）。
 *
 * <p>引入 {@link RefundPlanningAgent} 生成结构化 {@link RefundPlan}，
 * 并通过 {@link RefundInformationGatheringPolicy} 校验计划合法性。
 * 当计划步骤执行失败或发现新的缺失信息时，支持最多 3 次 RePlan。</p>
 */
public final class RefundInformationGatherer {

    private static final int MAX_REPLAN_ATTEMPTS = 3;
    private static final String REPLAN_BUDGET_EXHAUSTED = "REPLAN_BUDGET_EXHAUSTED";

    private final IAfterSalesToolPort toolPort;
    private final RefundPlanningAgent planningAgent;
    private final RefundInformationGatheringPolicy policy;
    private final AfterSalesRefundEligibilityPolicy eligibilityPolicy;
    private final TodoWriteTool todoWriteTool;

    public RefundInformationGatherer(IAfterSalesToolPort toolPort,
                                      RefundPlanningAgent planningAgent,
                                      RefundInformationGatheringPolicy policy,
                                      TodoWriteTool todoWriteTool) {
        this.toolPort = toolPort;
        this.planningAgent = planningAgent;
        this.policy = policy;
        this.eligibilityPolicy = new AfterSalesRefundEligibilityPolicy();
        this.todoWriteTool = todoWriteTool;
    }

    public AfterSalesAgentState gather(AfterSalesAgentState state) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        data.remove(AfterSalesAgentState.NEED_USER_INPUT);
        data.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());

        AfterSalesAgentState current = new AfterSalesAgentState(data);
        int replanCount = current.count(AfterSalesAgentState.REPLAN_COUNT);

        while (true) {
            PlanningContext context = buildContext(current, replanCount);
            RefundPlan plan = planningAgent.plan(context);

            RefundInformationGatheringPolicy.ValidationResult validation = policy.validate(plan, context);
            if (!validation.ok()) {
                plan = planningAgent.deterministicPlan(context);
            }

            PlanStep askStep = findAskUserStep(plan);
            if (askStep != null) {
                return askUser(current, askStep.reasonForUser());
            }

            ToolExecutionResult execution = executeToolSteps(current, plan);
            current = execution.state();

            boolean ready = !execution.failed()
                    && (plan.readyToEvaluate() || isReadyToEvaluate(current));
            if (ready) {
                return evaluateEligibility(current);
            }

            replanCount++;
            current = prepareReplanState(current, replanCount, execution.errorType(), execution.errorMessage());
            if (replanCount > MAX_REPLAN_ATTEMPTS) {
                return reject(current, REPLAN_BUDGET_EXHAUSTED);
            }
        }
    }

    private PlanningContext buildContext(AfterSalesAgentState state, int replanCount) {
        return new PlanningContext(
                state.text(AfterSalesAgentState.USER_ID),
                state.text(AfterSalesAgentState.SESSION_ID),
                state.text(AfterSalesAgentState.USER_MESSAGE),
                state.text(AfterSalesAgentState.ORDER_ID),
                state.text(AfterSalesAgentState.ORDER_STATUS),
                state.text(AfterSalesAgentState.REFUND_REASON),
                state.text(AfterSalesAgentState.TOOL_OUTPUT),
                previousToolError(state),
                state.count(AfterSalesAgentState.RETRY_COUNT),
                replanCount,
                state.text(AfterSalesAgentState.LAST_ERROR_TYPE),
                state.text(AfterSalesAgentState.LAST_ERROR_MESSAGE)
        );
    }

    private String previousToolError(AfterSalesAgentState state) {
        String terminalReason = state.text(AfterSalesAgentState.TERMINAL_REASON);
        if (terminalReason != null && !terminalReason.isBlank()
                && state.stage() != AfterSalesStage.COMPLETED
                && state.stage() != AfterSalesStage.REJECTED) {
            return terminalReason;
        }
        return null;
    }

    private PlanStep findAskUserStep(RefundPlan plan) {
        if (plan.steps() == null) {
            return null;
        }
        return plan.steps().stream()
                .filter(s -> RefundInformationGatheringPolicy.ACTION_ASK_USER.equals(s.action()))
                .findFirst()
                .orElse(null);
    }

    private AfterSalesAgentState askUser(AfterSalesAgentState state, String reason) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());
        update.put(AfterSalesAgentState.NEED_USER_INPUT, true);
        update.put(AfterSalesAgentState.DECISION_REASON,
                reason != null && !reason.isBlank() ? reason : "MISSING_REQUIRED_INFORMATION");
        return new AfterSalesAgentState(update);
    }

    private ToolExecutionResult executeToolSteps(AfterSalesAgentState state, RefundPlan plan) {
        AfterSalesAgentState current = state;
        if (plan.steps() == null) {
            return new ToolExecutionResult(current, false, null, null);
        }
        for (PlanStep step : plan.steps()) {
            if (!RefundInformationGatheringPolicy.ACTION_TOOL_CALL.equals(step.action())) {
                continue;
            }
            ToolExecutionResult result = executeToolCall(current, step);
            current = result.state();
            if (result.failed()) {
                return result;
            }
        }
        return new ToolExecutionResult(current, false, null, null);
    }

    private ToolExecutionResult executeToolCall(AfterSalesAgentState state, PlanStep step) {
        if (!RefundInformationGatheringPolicy.TOOL_QUERY_ORDER.equals(step.toolName())) {
            return toolFailure(state, "TOOL_NOT_ALLOWED", "TOOL_NOT_ALLOWED");
        }
        String orderId = step.input() == null ? null : String.valueOf(step.input().get("orderId"));
        if (orderId == null || orderId.isBlank()) {
            orderId = state.text(AfterSalesAgentState.ORDER_ID);
        }
        try {
            AfterSalesToolRequest request = toolPort.proposeOrderQuery(
                    state.text(AfterSalesAgentState.USER_MESSAGE),
                    state.text(AfterSalesAgentState.USER_ID),
                    state.text(AfterSalesAgentState.SESSION_ID),
                    orderId,
                    state.text(AfterSalesAgentState.REFUND_REASON),
                    state.text(AfterSalesAgentState.DECISION_REASON)
            );
            AfterSalesToolResult result = toolPort.executeOrderQuery(
                    request,
                    state.text(AfterSalesAgentState.USER_ID),
                    state.text(AfterSalesAgentState.USER_MESSAGE)
            );
            Map<String, Object> update = new LinkedHashMap<>(state.data());
            update.put(AfterSalesAgentState.TOOL_OUTPUT, result.outputJson());
            update.put(AfterSalesAgentState.TOOL_NAME, request.toolName());
            if (!result.success() || result.order() == null) {
                String errorType = result.errorType() != null ? result.errorType() : "TOOL_FAILURE";
                String errorMessage = result.errorMessage() != null ? result.errorMessage() : "TOOL_FAILURE";
                update.put(AfterSalesAgentState.LAST_ERROR_TYPE, errorType);
                update.put(AfterSalesAgentState.LAST_ERROR_MESSAGE, errorMessage);
                return new ToolExecutionResult(new AfterSalesAgentState(update), true, errorType, errorMessage);
            }
            AfterSalesOrderSnapshot order = result.order();
            update.put(AfterSalesAgentState.ORDER_ID, order.orderId());
            update.put(AfterSalesAgentState.ORDER_OWNER_ID, order.ownerId());
            update.put(AfterSalesAgentState.ORDER_STATUS, order.status());
            if (order.daysSinceDelivery() != null) {
                update.put(AfterSalesAgentState.DAYS_SINCE_DELIVERY, order.daysSinceDelivery());
            }
            update.remove(AfterSalesAgentState.LAST_ERROR_TYPE);
            update.remove(AfterSalesAgentState.LAST_ERROR_MESSAGE);
            return new ToolExecutionResult(new AfterSalesAgentState(update), false, null, null);
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "ORDER_QUERY_FAILED";
            return toolFailure(state, "TOOL_EXCEPTION", errorMessage);
        }
    }

    private ToolExecutionResult toolFailure(AfterSalesAgentState state, String errorType, String errorMessage) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.LAST_ERROR_TYPE, errorType);
        update.put(AfterSalesAgentState.LAST_ERROR_MESSAGE, errorMessage);
        return new ToolExecutionResult(new AfterSalesAgentState(update), true, errorType, errorMessage);
    }

    private AfterSalesAgentState prepareReplanState(AfterSalesAgentState state, int replanCount,
                                                    String errorType, String errorMessage) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.REPLAN_COUNT, replanCount);
        if (errorType != null) {
            update.put(AfterSalesAgentState.LAST_ERROR_TYPE, errorType);
        }
        if (errorMessage != null) {
            update.put(AfterSalesAgentState.LAST_ERROR_MESSAGE, errorMessage);
            update.put(AfterSalesAgentState.TERMINAL_REASON, errorMessage);
        }
        return new AfterSalesAgentState(update);
    }

    private boolean isReadyToEvaluate(AfterSalesAgentState state) {
        return state.hasText(AfterSalesAgentState.USER_ID)
                && state.hasText(AfterSalesAgentState.ORDER_ID)
                && state.hasText(AfterSalesAgentState.ORDER_STATUS)
                && state.hasText(AfterSalesAgentState.REFUND_REASON);
    }

    private AfterSalesAgentState evaluateEligibility(AfterSalesAgentState state) {
        AfterSalesRefundEligibilityPolicy.RefundDecision decision = eligibilityPolicy.evaluate(
                new AfterSalesRefundEligibilityPolicy.RefundRequest(
                        state.text(AfterSalesAgentState.USER_ID),
                        state.text(AfterSalesAgentState.ORDER_ID),
                        state.text(AfterSalesAgentState.ORDER_OWNER_ID),
                        state.text(AfterSalesAgentState.ORDER_STATUS),
                        state.text(AfterSalesAgentState.REFUND_REASON),
                        state.nullableInteger(AfterSalesAgentState.DAYS_SINCE_DELIVERY)
                )
        );

        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.DECISION_REASON, decision.reason());
        switch (decision.outcome()) {
            case NEED_USER_INPUT -> {
                update.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());
                update.put(AfterSalesAgentState.NEED_USER_INPUT, true);
            }
            case ELIGIBLE -> {
                update.put(AfterSalesAgentState.STAGE, AfterSalesStage.PENDING_APPROVAL.name());
                update.put(AfterSalesAgentState.ELIGIBLE, true);
                update.remove(AfterSalesAgentState.TERMINAL_REASON);
                update.put(AfterSalesAgentState.CHECKLIST, buildRefundChecklist(state));
                writeTodoChecklist(state);
            }
            case ALREADY_COMPLETED -> {
                update.put(AfterSalesAgentState.STAGE, AfterSalesStage.COMPLETED.name());
                update.put(AfterSalesAgentState.TERMINAL_REASON, decision.reason());
            }
            case REJECTED -> {
                update.put(AfterSalesAgentState.STAGE, AfterSalesStage.REJECTED.name());
                update.put(AfterSalesAgentState.TERMINAL_REASON, decision.reason());
            }
        }
        return new AfterSalesAgentState(update);
    }

    private AfterSalesAgentState reject(AfterSalesAgentState state, String reason) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.STAGE, AfterSalesStage.REJECTED.name());
        update.put(AfterSalesAgentState.TERMINAL_REASON, reason);
        return new AfterSalesAgentState(update);
    }

    private List<Map<String, String>> buildRefundChecklist(AfterSalesAgentState state) {
        String orderId = state.text(AfterSalesAgentState.ORDER_ID);
        String refundReason = state.text(AfterSalesAgentState.REFUND_REASON);
        List<Map<String, String>> checklist = new ArrayList<>(4);
        checklist.add(Map.of("id", "1", "content", "订单号已确认: " + orderId, "status", "done"));
        checklist.add(Map.of("id", "2", "content", "用户身份已校验", "status", "done"));
        checklist.add(Map.of("id", "3", "content", "退款原因已记录: " + refundReason, "status", "done"));
        checklist.add(Map.of("id", "4", "content", "等待人工审批", "status", "pending"));
        return checklist;
    }

    private void writeTodoChecklist(AfterSalesAgentState state) {
        if (todoWriteTool == null) {
            return;
        }
        String orderId = state.text(AfterSalesAgentState.ORDER_ID);
        String refundReason = state.text(AfterSalesAgentState.REFUND_REASON);
        TodoWriteTool.Todos todos = new TodoWriteTool.Todos(List.of(
                new TodoWriteTool.Todos.TodoItem("订单号已确认: " + orderId,
                        TodoWriteTool.Todos.Status.completed, "确认订单号中"),
                new TodoWriteTool.Todos.TodoItem("用户身份已校验",
                        TodoWriteTool.Todos.Status.completed, "校验用户身份中"),
                new TodoWriteTool.Todos.TodoItem("退款原因已记录: " + refundReason,
                        TodoWriteTool.Todos.Status.completed, "记录退款原因中"),
                new TodoWriteTool.Todos.TodoItem("等待人工审批",
                        TodoWriteTool.Todos.Status.pending, "等待人工审批中")
        ));
        todoWriteTool.todoWrite(todos);
    }

    private record ToolExecutionResult(AfterSalesAgentState state,
                                       boolean failed,
                                       String errorType,
                                       String errorMessage) {
    }
}
