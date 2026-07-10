package cn.ethan.ai.domain.agent.policy;

import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.domain.agent.model.AfterSalesToolCapability;

import java.util.Set;

/**
 * 退款信息收集计划校验策略。
 *
 * <p>确保规划 Agent 输出的 {@link RefundPlan} 符合白名单约束、工具约束以及收敛性约束。</p>
 */
public final class RefundInformationGatheringPolicy {

    public static final String ACTION_ASK_USER = "ASK_USER";
    public static final String ACTION_TOOL_CALL = "TOOL_CALL";
    public static final String TOOL_QUERY_ORDER = "query_order";

    private static final Set<String> ALLOWED_ACTIONS = Set.of(ACTION_ASK_USER, ACTION_TOOL_CALL);
    private static final Set<String> KNOWN_FIELDS = Set.of("userId", "orderId", "orderStatus", "refundReason");
    private static final Set<String> ALLOWED_REASON_CODES = Set.of(
            "MISSING_ORDER_ID", "MISSING_REFUND_REASON", "MISSING_ORDER_STATUS",
            "TOOL_FAILED", "RETRY_CONFIRMATION", "EVIDENCE_REQUIRED");

    public ValidationResult validate(RefundPlan plan, PlanningContext context) {
        if (plan == null) {
            return ValidationResult.invalid("PLAN_NULL", "计划不能为空");
        }
        if (plan.schemaVersion() != 1) {
            return ValidationResult.invalid("PLAN_VERSION_UNSUPPORTED", "不支持的计划版本");
        }
        ValidationResult evidenceValidation = validateEvidenceGaps(plan, context);
        if (!evidenceValidation.ok()) {
            return evidenceValidation;
        }
        if (plan.steps() == null || plan.steps().isEmpty()) {
            if (plan.readyToEvaluate()) {
                return ValidationResult.valid();
            }
            return ValidationResult.invalid("PLAN_EMPTY", "非评估状态下计划步骤不能为空");
        }
        for (PlannedStep step : plan.steps()) {
            ValidationResult stepValidation = validateStep(step, context);
            if (!stepValidation.ok()) {
                return stepValidation;
            }
        }
        ValidationResult convergenceValidation = validateConvergence(plan, context);
        if (!convergenceValidation.ok()) {
            return convergenceValidation;
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateEvidenceGaps(RefundPlan plan, PlanningContext context) {
        if (plan.evidenceGaps() == null) {
            return ValidationResult.invalid("EVIDENCE_GAP_MISSING", "计划必须声明证据缺口");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (var gap : plan.evidenceGaps()) {
            if (gap == null || gap.field() == null || !KNOWN_FIELDS.contains(gap.field())) {
                return ValidationResult.invalid("EVIDENCE_FIELD_INVALID", "证据字段不合法");
            }
            if (!seen.add(gap.field())) {
                return ValidationResult.invalid("EVIDENCE_GAP_DUPLICATED", "证据缺口不能重复");
            }
            if (gap.source() == null || gap.reasonCode() == null || !ALLOWED_REASON_CODES.contains(gap.reasonCode())) {
                return ValidationResult.invalid("EVIDENCE_GAP_INVALID", "证据缺口来源或原因不合法");
            }
        }
        boolean ready = hasText(context.userId()) && hasText(context.orderId())
                && hasText(context.orderStatus()) && hasText(context.refundReason());
        if (plan.readyToEvaluate() != ready) {
            return ValidationResult.invalid("READY_STATE_CONFLICT", "计划完成状态与可信上下文不一致");
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateStep(PlannedStep step, PlanningContext context) {
        String action = step.action();
        if (action == null || !ALLOWED_ACTIONS.contains(action)) {
            return ValidationResult.invalid("ACTION_NOT_ALLOWED",
                    "不允许的动作: " + action + "，只允许 " + ALLOWED_ACTIONS);
        }
        if (ACTION_TOOL_CALL.equals(action)) {
            AfterSalesToolCapability capability = AfterSalesToolCapability.fromToolName(step.toolName());
            if (capability == null || context.availableTools() == null || !context.availableTools().contains(capability)) {
                return ValidationResult.invalid("TOOL_NOT_ALLOWED",
                        "当前运行时不允许的工具: " + step.toolName());
            }
            if (step.input() == null || !step.input().containsKey("orderId")) {
                return ValidationResult.invalid("TOOL_INPUT_INVALID",
                        "query_order 必须包含 orderId 参数");
            }
        }
        if (ACTION_ASK_USER.equals(action)) {
            if (step.targetField() == null || step.targetField().isBlank()) {
                return ValidationResult.invalid("ASK_USER_FIELD_MISSING",
                        "ASK_USER 步骤必须指定 targetField");
            }
        }
        if (step.reasonCode() != null && !ALLOWED_REASON_CODES.contains(step.reasonCode())) {
            return ValidationResult.invalid("STEP_REASON_INVALID", "步骤原因不合法");
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateConvergence(RefundPlan plan, PlanningContext context) {
        boolean allowRetry = context.retryCount() > 0
                || context.replanCount() > 0
                || (context.previousToolError() != null && !context.previousToolError().isBlank());
        if (allowRetry) {
            return ValidationResult.valid();
        }
        Set<String> presentFields = presentFields(context);
        for (PlannedStep step : plan.steps()) {
            if (ACTION_ASK_USER.equals(step.action())
                    && step.targetField() != null
                    && presentFields.contains(step.targetField())) {
                return ValidationResult.invalid("NON_CONVERGENT_PLAN",
                        "已经存在字段 " + step.targetField() + "，不应再次询问");
            }
        }
        return ValidationResult.valid();
    }

    private Set<String> presentFields(PlanningContext context) {
        Set<String> fields = new java.util.HashSet<>();
        if (hasText(context.userId())) {
            fields.add("userId");
        }
        if (hasText(context.orderId())) {
            fields.add("orderId");
        }
        if (hasText(context.orderStatus())) {
            fields.add("orderStatus");
        }
        if (hasText(context.refundReason())) {
            fields.add("refundReason");
        }
        return fields;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ValidationResult(boolean ok, String errorType, String message) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult invalid(String errorType, String message) {
            return new ValidationResult(false, errorType, message);
        }
    }
}
