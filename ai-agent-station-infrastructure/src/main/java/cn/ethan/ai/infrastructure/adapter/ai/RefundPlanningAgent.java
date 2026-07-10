package cn.ethan.ai.infrastructure.adapter.ai;

import cn.ethan.ai.domain.agent.model.plan.ChecklistItem;
import cn.ethan.ai.domain.agent.model.plan.EvidenceGap;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.types.common.id.StepId;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.session.advisor.SessionMemoryAdvisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static cn.ethan.ai.types.common.util.Strings.isBlank;

/**
 * 退款信息收集规划 Agent（Phase 7.2）。
 *
 * <p>基于 Spring AI {@link ChatClient} 与 {@link SessionMemoryAdvisor}，
 * 根据当前上下文生成结构化的 {@link RefundPlan}。当 ChatClient 不可用时退化为确定性计划。</p>
 */
@Slf4j
public class RefundPlanningAgent {

    private static final String SYSTEM_PROMPT = """
            你是售后退款信息收集规划助手。你的唯一任务是根据当前已收集的上下文，生成下一步信息收集计划。
            只输出符合 RefundPlan 模式的合法 JSON，不要输出任何解释、markdown 代码块或其他文本。
            可用的动作：
            - ASK_USER: 向用户询问某个字段
            - TOOL_CALL: 调用当前可用的只读证据工具

            规则：
            1. 只能调用上下文 availableTools 中列出的工具，禁止生成任何其他工具调用。
            2. 禁止生成退款、审批、执行、退款确认等动作。
            3. 如果已具备 userId、orderId、orderStatus、refundReason，则 readyToEvaluate=true。
            4. 如果 orderId 已知但 orderStatus 未知，优先生成 TOOL_CALL query_order。
            5. 如果之前工具调用失败或缺少必要信息，请根据当前 RePlan 次数与错误信息调整下一步计划；
               若 RePlan 次数已较高，优先改为 ASK_USER 确认缺失或错误的信息，避免无限循环。
            6. evidenceGaps 必须列出当前缺失字段、来源（USER|TOOL）和受限 reasonCode。
            7. checklist 必须列出 userId、orderId、orderStatus、refundReason 及其状态（PENDING/DONE）。
            RefundPlan JSON 模式：
            {
              "schemaVersion": 1,
              "readyToEvaluate": boolean,
              "evidenceGaps": [{"field":"字段名","source":"USER|TOOL","reasonCode":"MISSING_ORDER_ID|MISSING_REFUND_REASON|MISSING_ORDER_STATUS|TOOL_FAILED|RETRY_CONFIRMATION|EVIDENCE_REQUIRED"}],
              "steps": [
                {
                  "action": "ASK_USER|TOOL_CALL",
                  "targetField": "字段名",
                  "toolName": "query_order（仅 TOOL_CALL）",
                  "input": {"orderId": "..."},
                  "reasonForUser": "给用户看的原因",
                  "reasonCode": "受限原因码",
                  "expectedEvidence": "此步骤应补齐的字段"
                }
              ],
              "checklist": [
                {"item": "字段名", "status": "PENDING|DONE"}
              ]
            }
            """;

    private final ChatClient chatClient;
    private final AfterSalesRuntimeMetrics metrics;

    public RefundPlanningAgent(ChatClient chatClient) {
        this(chatClient, AfterSalesRuntimeMetrics.noop());
    }

    public RefundPlanningAgent(ChatClient chatClient, AfterSalesRuntimeMetrics metrics) {
        this.chatClient = chatClient;
        this.metrics = metrics;
    }

    public RefundPlan plan(PlanningContext context) {
        long startedAt = System.nanoTime();
        String outcome = "model";
        RefundPlan plan;
        if (chatClient == null) {
            plan = deterministicPlan(context);
            outcome = "deterministic";
        } else {
            try {
                String content = chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildUserPrompt(context))
                        .advisors(a -> a
                                .param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, memoryIdFor(context))
                                .param(SessionMemoryAdvisor.USER_ID_CONTEXT_KEY, userIdFor(context)))
                        .call()
                        .content();
                if (content == null || content.isBlank()) {
                    log.debug("RefundPlanningAgent received empty response, using fallback");
                    plan = fallbackPlan(context);
                    outcome = "fallback_empty";
                } else {
                    plan = JSON.parseObject(content, RefundPlan.class);
                }
            } catch (Exception e) {
                log.debug("RefundPlanningAgent failed to parse plan, using fallback: {}", e.getMessage());
                plan = fallbackPlan(context);
                outcome = "fallback_error";
            }
        }
        metrics.recordModelPlan(System.nanoTime() - startedAt, outcome);
        return assignStepIds(plan);
    }

    /**
     * 确定性计划生成：不依赖大模型，用于 ChatClient 缺失或模型输出异常时的安全兜底。
     */
    public RefundPlan deterministicPlan(PlanningContext context) {
        List<PlannedStep> steps = new ArrayList<>();
        List<ChecklistItem> checklist = new ArrayList<>();
        List<EvidenceGap> gaps = evidenceGaps(context);

        checklist.add(item("userId", context.userId()));
        checklist.add(item("orderId", context.orderId()));
        checklist.add(item("orderStatus", context.orderStatus()));
        checklist.add(item("refundReason", context.refundReason()));

        if (isBlank(context.userId())) {
            steps.add(askUserStep("userId", "MISSING_REQUIRED_IDENTITY", "MISSING_ORDER_ID"));
        }
        if (isBlank(context.orderId())) {
            steps.add(askUserStep("orderId", "MISSING_REQUIRED_IDENTITY", "MISSING_ORDER_ID"));
        }
        boolean hasOrderId = !isBlank(context.orderId());
        boolean hasOrderStatus = !isBlank(context.orderStatus());
        boolean previousToolFailed = !isBlank(context.previousToolError());
        if (hasOrderId && !hasOrderStatus && !previousToolFailed) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("orderId", context.orderId());
            steps.add(toolCallStep("orderStatus", "query_order", input, "MISSING_ORDER_STATUS"));
        }
        if (hasOrderId && !hasOrderStatus && previousToolFailed) {
            steps.add(askUserStep("orderId", "ORDER_QUERY_FAILED", "TOOL_FAILED"));
        }
        if (isBlank(context.refundReason())) {
            steps.add(askUserStep("refundReason", "MISSING_REQUIRED_INFORMATION", "MISSING_REFUND_REASON"));
        }

        boolean ready = !isBlank(context.userId())
                && !isBlank(context.orderId())
                && !isBlank(context.orderStatus())
                && !isBlank(context.refundReason());
        return assignStepIds(new RefundPlan(1, ready, gaps, steps, checklist));
    }

    private RefundPlan fallbackPlan(PlanningContext context) {
        List<PlannedStep> steps = new ArrayList<>();
        if (isBlank(context.orderId())) {
            steps.add(askUserStep("orderId", "MISSING_REQUIRED_IDENTITY", "MISSING_ORDER_ID"));
        }
        if (isBlank(context.refundReason())) {
            steps.add(askUserStep("refundReason", "MISSING_REQUIRED_INFORMATION", "MISSING_REFUND_REASON"));
        }
        List<ChecklistItem> checklist = List.of(
                item("userId", context.userId()),
                item("orderId", context.orderId()),
                item("orderStatus", context.orderStatus()),
                item("refundReason", context.refundReason())
        );
        return assignStepIds(new RefundPlan(1, false, evidenceGaps(context), steps, checklist));
    }

    private PlannedStep askUserStep(String targetField, String reasonForUser, String reasonCode) {
        PlannedStep step = new PlannedStep(null, "ASK_USER", targetField, null, null,
                reasonForUser, reasonCode, targetField);
        return new PlannedStep(stepIdOf(step), step.action(), step.targetField(), step.toolName(), step.input(),
                step.reasonForUser(), step.reasonCode(), step.expectedEvidence());
    }

    private PlannedStep toolCallStep(String targetField, String toolName, Map<String, Object> input, String reasonCode) {
        PlannedStep step = new PlannedStep(null, "TOOL_CALL", targetField, toolName, input, null,
                reasonCode, targetField);
        return new PlannedStep(stepIdOf(step), step.action(), step.targetField(), step.toolName(), step.input(),
                step.reasonForUser(), step.reasonCode(), step.expectedEvidence());
    }

    private RefundPlan assignStepIds(RefundPlan plan) {
        if (plan == null || plan.steps() == null) {
            return plan;
        }
        List<PlannedStep> steps = new ArrayList<>(plan.steps().size());
        for (PlannedStep step : plan.steps()) {
            StepId stepId = step.stepId() != null ? step.stepId() : stepIdOf(step);
            steps.add(new PlannedStep(stepId, step.action(), step.targetField(), step.toolName(), step.input(),
                    step.reasonForUser(), step.reasonCode(), step.expectedEvidence()));
        }
        return new RefundPlan(plan.schemaVersion(), plan.readyToEvaluate(),
                plan.evidenceGaps() == null ? List.of() : plan.evidenceGaps(), steps, plan.checklist());
    }

    private static StepId stepIdOf(PlannedStep step) {
        String input = step.input() == null ? "" : canonicalJson(step.input());
        return StepId.of(step.action() + "|" + step.toolName() + "|" + step.targetField() + "|" + input);
    }

    private static String canonicalJson(Map<String, Object> map) {
        return JSON.toJSONString(new TreeMap<>(map), SerializerFeature.MapSortField);
    }

    private ChecklistItem item(String field, String value) {
        return new ChecklistItem(field, isBlank(value) ? "PENDING" : "DONE");
    }

    private String buildUserPrompt(PlanningContext context) {
        return "当前上下文：\n"
                + "- userId: " + safe(context.userId()) + "\n"
                + "- sessionId: " + safe(context.sessionId()) + "\n"
                + "- 用户消息: " + safe(context.message()) + "\n"
                + "- orderId: " + safe(context.orderId()) + "\n"
                + "- orderStatus: " + safe(context.orderStatus()) + "\n"
                + "- refundReason: " + safe(context.refundReason()) + "\n"
                + "- 上次工具输出: " + safe(context.previousToolOutput()) + "\n"
                + "- 上次工具错误: " + safe(context.previousToolError()) + "\n"
                + "- retryCount: " + context.retryCount() + "\n"
                + "- replanCount: " + context.replanCount() + "\n"
                + "- lastErrorType: " + safe(context.lastErrorType()) + "\n"
                + "- lastErrorMessage: " + safe(context.lastErrorMessage()) + "\n"
                + "- availableTools: " + context.availableTools() + "\n"
                + "- trustedEvidence: " + context.evidence();
    }

    private List<EvidenceGap> evidenceGaps(PlanningContext context) {
        List<EvidenceGap> gaps = new ArrayList<>();
        if (isBlank(context.userId())) {
            gaps.add(new EvidenceGap("userId", EvidenceGap.EvidenceSource.USER, "MISSING_ORDER_ID"));
        }
        if (isBlank(context.orderId())) {
            gaps.add(new EvidenceGap("orderId", EvidenceGap.EvidenceSource.USER, "MISSING_ORDER_ID"));
        }
        if (!isBlank(context.orderId()) && isBlank(context.orderStatus())) {
            gaps.add(new EvidenceGap("orderStatus", EvidenceGap.EvidenceSource.TOOL,
                    isBlank(context.previousToolError()) ? "MISSING_ORDER_STATUS" : "TOOL_FAILED"));
        }
        if (isBlank(context.refundReason())) {
            gaps.add(new EvidenceGap("refundReason", EvidenceGap.EvidenceSource.USER, "MISSING_REFUND_REASON"));
        }
        return gaps;
    }

    private String memoryIdFor(PlanningContext context) {
        String caseId = context.caseId();
        return caseId != null && !caseId.isBlank() ? caseId : "anonymous-case";
    }

    private String userIdFor(PlanningContext context) {
        String userId = context.userId();
        return userId != null && !userId.isBlank() ? userId : "anonymous-user";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

}
