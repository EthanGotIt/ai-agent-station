package cn.ethan.ai.infrastructure.adapter.statemachine.ssm;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.policy.AfterSalesRefundEligibilityPolicy;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.CheckpointId;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.types.common.id.TurnId;
import org.springaicommunity.agent.tools.TodoWriteTool;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 退款信息收集器（Phase 7.3）。
 *
 * <p>引入 {@link RefundPlanningAgent} 生成结构化 {@link RefundPlan}，
 * 并通过 {@link RefundInformationGatheringPolicy} 校验计划合法性。
 * 当计划步骤执行失败或发现新的缺失信息时，支持最多 3 次 RePlan。
 * 每次状态变更后通过 {@link ICheckpointRepository} 持久化 Checkpoint。</p>
 */
public final class RefundInformationGatherer {

    private static final int MAX_REPLAN_ATTEMPTS = 3;
    private static final int MAX_STEP_RETRY = 2;
    private static final String REPLAN_BUDGET_EXHAUSTED = "REPLAN_BUDGET_EXHAUSTED";

    private final IAfterSalesToolPort toolPort;
    private final RefundPlanningAgent planningAgent;
    private final RefundInformationGatheringPolicy policy;
    private final AfterSalesRefundEligibilityPolicy eligibilityPolicy;
    private final TodoWriteTool todoWriteTool;
    private final ICheckpointRepository checkpointRepository;
    private final AfterSalesRuntimeMetrics metrics;

    public RefundInformationGatherer(IAfterSalesToolPort toolPort,
                                     RefundPlanningAgent planningAgent,
                                     RefundInformationGatheringPolicy policy,
                                     TodoWriteTool todoWriteTool,
                                     ICheckpointRepository checkpointRepository) {
        this(toolPort, planningAgent, policy, todoWriteTool,
                checkpointRepository, AfterSalesRuntimeMetrics.noop());
    }

    public RefundInformationGatherer(IAfterSalesToolPort toolPort,
                                      RefundPlanningAgent planningAgent,
                                      RefundInformationGatheringPolicy policy,
                                      TodoWriteTool todoWriteTool,
                                      ICheckpointRepository checkpointRepository,
                                      AfterSalesRuntimeMetrics metrics) {
        this.toolPort = toolPort;
        this.planningAgent = planningAgent;
        this.policy = policy;
        this.eligibilityPolicy = new AfterSalesRefundEligibilityPolicy();
        this.todoWriteTool = todoWriteTool;
        this.checkpointRepository = checkpointRepository;
        this.metrics = metrics;
    }

    public AfterSalesAgentState gather(AfterSalesAgentState state, CaseId caseId, TurnId turnId) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        data.remove(AfterSalesAgentState.NEED_USER_INPUT);
        data.remove(AfterSalesAgentState.CURRENT_STEP_KEY);
        data.remove(AfterSalesAgentState.CURRENT_STEP_ATTEMPT_COUNT);
        data.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());

        AfterSalesAgentState current = new AfterSalesAgentState(data);
        writeCheckpoint(current, caseId, turnId, null);

        int replanCount = current.count(AfterSalesAgentState.REPLAN_COUNT);
        RefundPlan currentPlan = null;
        boolean replanNeeded = true;

        while (true) {
            // 只有在首次进入或明确需要 RePlan 时才重新生成计划；
            // 同一步骤失败重试期间保持当前计划不变。
            if (replanNeeded || currentPlan == null) {
                PlanningContext context = buildContext(current, replanCount);
                RefundPlan plan = planningAgent.plan(context);

                RefundInformationGatheringPolicy.ValidationResult validation = policy.validate(plan, context);
                if (!validation.ok()) {
                    plan = planningAgent.deterministicPlan(context);
                }
                currentPlan = plan;
                replanNeeded = false;

                PlannedStep askStep = findAskUserStep(currentPlan);
                if (askStep != null) {
                    AfterSalesAgentState askState = askUser(current, askStep.reasonForUser());
                    writeCheckpoint(askState, caseId, turnId, askStep.stepId());
                    return askState;
                }
            }

            PlannedStep nextStep = selectNextPendingStep(currentPlan, current);
            if (nextStep == null) {
                if (currentPlan.readyToEvaluate() || isReadyToEvaluate(current)) {
                    AfterSalesAgentState evaluated = evaluateEligibility(current);
                    writeCheckpoint(evaluated, caseId, turnId, null);
                    return evaluated;
                }
                // 计划无待执行步骤但信息仍不完整，触发一次 RePlan
                replanCount++;
                metrics.recordReplan("NO_PENDING_STEP");
                current = prepareReplanState(current, replanCount, "NO_PENDING_STEP", "计划无待执行步骤且信息不完整");
                writeCheckpoint(current, caseId, turnId, null);
                currentPlan = null;
                if (replanCount > MAX_REPLAN_ATTEMPTS) {
                    AfterSalesAgentState rejected = reject(current, REPLAN_BUDGET_EXHAUSTED);
                    writeCheckpoint(rejected, caseId, turnId, null);
                    return rejected;
                }
                continue;
            }

            ToolExecutionResult execution = executeToolStep(current, nextStep);
            current = execution.state();
            writeCheckpoint(current, caseId, turnId, nextStep.stepId());

            if (!execution.failed()) {
                // 通过：更新进度，清空当前计划让下一次循环重新规划剩余工作
                current = markStepDone(current, nextStep);
                writeCheckpoint(current, caseId, turnId, nextStep.stepId());
                currentPlan = null;
                continue;
            }

            // 失败：先重试同一步，重试耗尽后再 RePlan
            int stepAttempt = current.count(AfterSalesAgentState.CURRENT_STEP_ATTEMPT_COUNT);
            if (stepAttempt <= MAX_STEP_RETRY) {
                current = incrementStepAttempt(current);
                writeCheckpoint(current, caseId, turnId, nextStep.stepId());
                continue;
            }

            replanCount++;
            metrics.recordReplan(execution.errorType());
            current = prepareReplanState(current, replanCount, execution.errorType(), execution.errorMessage());
            writeCheckpoint(current, caseId, turnId, null);
            currentPlan = null;
            if (replanCount > MAX_REPLAN_ATTEMPTS) {
                AfterSalesAgentState rejected = reject(current, REPLAN_BUDGET_EXHAUSTED);
                writeCheckpoint(rejected, caseId, turnId, null);
                return rejected;
            }
        }
    }

    private void writeCheckpoint(AfterSalesAgentState state, CaseId caseId, TurnId turnId, StepId stepId) {
        if (checkpointRepository == null) {
            return;
        }
        Checkpoint checkpoint = new Checkpoint(
                CheckpointId.of(java.util.UUID.randomUUID().toString()),
                caseId,
                turnId,
                stepId,
                ssmStateOf(state.stage()),
                state,
                state.stage(),
                LocalDateTime.now()
        );
        checkpointRepository.save(checkpoint);
        metrics.recordCheckpoint("process", state.stage().name());
    }

    private static String ssmStateOf(AfterSalesStage stage) {
        return switch (stage) {
            case INTAKE -> AfterSalesState.INTAKE.name();
            case PENDING_APPROVAL -> AfterSalesState.PENDING_APPROVAL.name();
            case COMPLETED -> AfterSalesState.COMPLETED.name();
            case REJECTED -> AfterSalesState.REJECTED.name();
        };
    }

    private PlanningContext buildContext(AfterSalesAgentState state, int replanCount) {
        return new PlanningContext(
                state.text(AfterSalesAgentState.CASE_ID),
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
        String errorMessage = state.text(AfterSalesAgentState.LAST_ERROR_MESSAGE);
        if (errorMessage != null && !errorMessage.isBlank()
                && state.stage() != AfterSalesStage.COMPLETED
                && state.stage() != AfterSalesStage.REJECTED) {
            return errorMessage;
        }
        return null;
    }

    private PlannedStep findAskUserStep(RefundPlan plan) {
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

    private PlannedStep selectNextPendingStep(RefundPlan plan, AfterSalesAgentState state) {
        if (plan.steps() == null) {
            return null;
        }
        Set<String> executed = state.executedStepKeys();
        return plan.steps().stream()
                .filter(s -> RefundInformationGatheringPolicy.ACTION_TOOL_CALL.equals(s.action()))
                .filter(s -> !executed.contains(s.stepId().value()))
                .findFirst()
                .orElse(null);
    }

    private AfterSalesAgentState markStepDone(AfterSalesAgentState state, PlannedStep step) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        Set<String> executed = state.executedStepKeys();
        executed = new HashSet<>(executed);
        executed.add(step.stepId().value());
        update.put(AfterSalesAgentState.EXECUTED_STEP_KEYS, executed);
        update.remove(AfterSalesAgentState.CURRENT_STEP_KEY);
        update.remove(AfterSalesAgentState.CURRENT_STEP_ATTEMPT_COUNT);
        update.remove(AfterSalesAgentState.LAST_ERROR_TYPE);
        update.remove(AfterSalesAgentState.LAST_ERROR_MESSAGE);
        return new AfterSalesAgentState(update);
    }

    private AfterSalesAgentState incrementStepAttempt(AfterSalesAgentState state) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.CURRENT_STEP_ATTEMPT_COUNT,
                update.getOrDefault(AfterSalesAgentState.CURRENT_STEP_ATTEMPT_COUNT, 0) instanceof Number n
                        ? n.intValue() + 1 : 1);
        return new AfterSalesAgentState(update);
    }

    private ToolExecutionResult executeToolStep(AfterSalesAgentState state, PlannedStep step) {
        AfterSalesAgentState current = rememberCurrentStep(state, step);
        return executeToolCall(current, step);
    }

    private AfterSalesAgentState rememberCurrentStep(AfterSalesAgentState state, PlannedStep step) {
        Map<String, Object> update = new LinkedHashMap<>(state.data());
        update.put(AfterSalesAgentState.CURRENT_STEP_KEY, step.stepId().value());
        return new AfterSalesAgentState(update);
    }

    private ToolExecutionResult executeToolCall(AfterSalesAgentState state, PlannedStep step) {
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
        update.remove(AfterSalesAgentState.CURRENT_STEP_KEY);
        update.remove(AfterSalesAgentState.CURRENT_STEP_ATTEMPT_COUNT);
        if (errorType != null) {
            update.put(AfterSalesAgentState.LAST_ERROR_TYPE, errorType);
        }
        if (errorMessage != null) {
            update.put(AfterSalesAgentState.LAST_ERROR_MESSAGE, errorMessage);
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
