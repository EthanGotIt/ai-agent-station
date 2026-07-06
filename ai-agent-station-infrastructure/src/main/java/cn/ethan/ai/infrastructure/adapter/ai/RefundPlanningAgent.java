package cn.ethan.ai.infrastructure.adapter.ai;

import cn.ethan.ai.domain.agent.model.plan.ChecklistItem;
import cn.ethan.ai.domain.agent.model.plan.PlannedStep;
import cn.ethan.ai.domain.agent.model.plan.PlanningContext;
import cn.ethan.ai.domain.agent.model.plan.RefundPlan;
import cn.ethan.ai.types.common.id.StepId;
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
            - TOOL_CALL: 调用 query_order 工具查询订单

            规则：
            1. 只允许调用 query_order 工具，禁止生成任何其他工具调用。
            2. 禁止生成退款、审批、执行、退款确认等动作。
            3. 如果已具备 userId、orderId、orderStatus、refundReason，则 readyToEvaluate=true。
            4. 如果 orderId 已知但 orderStatus 未知，优先生成 TOOL_CALL query_order。
            5. 如果之前工具调用失败或缺少必要信息，请根据当前 RePlan 次数与错误信息调整下一步计划；
               若 RePlan 次数已较高，优先改为 ASK_USER 确认缺失或错误的信息，避免无限循环。
            6. checklist 必须列出 userId、orderId、orderStatus、refundReason 及其状态（PENDING/DONE）。
            RefundPlan JSON 模式：
            {
              "readyToEvaluate": boolean,
              "steps": [
                {
                  "action": "ASK_USER|TOOL_CALL",
                  "targetField": "字段名",
                  "toolName": "query_order（仅 TOOL_CALL）",
                  "input": {"orderId": "..."},
                  "reasonForUser": "给用户看的原因"
                }
              ],
              "checklist": [
                {"item": "字段名", "status": "PENDING|DONE"}
              ]
            }
            """;

    private final ChatClient chatClient;

    public RefundPlanningAgent(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public RefundPlan plan(PlanningContext context) {
        RefundPlan plan;
        if (chatClient == null) {
            plan = deterministicPlan(context);
        } else {
            try {
                String content = chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user(buildUserPrompt(context))
                        .advisors(a -> a.param(SessionMemoryAdvisor.SESSION_ID_CONTEXT_KEY, sessionIdFor(context)))
                        .call()
                        .content();
                if (content == null || content.isBlank()) {
                    log.warn("RefundPlanningAgent received empty response, using fallback");
                    plan = fallbackPlan(context);
                } else {
                    plan = JSON.parseObject(content, RefundPlan.class);
                }
            } catch (Exception e) {
                log.warn("RefundPlanningAgent failed to parse plan, using fallback", e);
                plan = fallbackPlan(context);
            }
        }
        return assignStepIds(plan);
    }

    /**
     * 确定性计划生成：不依赖大模型，用于 ChatClient 缺失或模型输出异常时的安全兜底。
     */
    public RefundPlan deterministicPlan(PlanningContext context) {
        List<PlannedStep> steps = new ArrayList<>();
        List<ChecklistItem> checklist = new ArrayList<>();

        checklist.add(item("userId", context.userId()));
        checklist.add(item("orderId", context.orderId()));
        checklist.add(item("orderStatus", context.orderStatus()));
        checklist.add(item("refundReason", context.refundReason()));

        if (isBlank(context.userId())) {
            steps.add(askUserStep("userId", "MISSING_REQUIRED_IDENTITY"));
        }
        if (isBlank(context.orderId())) {
            steps.add(askUserStep("orderId", "MISSING_REQUIRED_IDENTITY"));
        }
        boolean hasOrderId = !isBlank(context.orderId());
        boolean hasOrderStatus = !isBlank(context.orderStatus());
        boolean previousToolFailed = !isBlank(context.previousToolError());
        if (hasOrderId && !hasOrderStatus && !previousToolFailed) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("orderId", context.orderId());
            steps.add(toolCallStep("orderStatus", "query_order", input));
        }
        if (hasOrderId && !hasOrderStatus && previousToolFailed) {
            steps.add(askUserStep("orderId", "ORDER_QUERY_FAILED"));
        }
        if (isBlank(context.refundReason())) {
            steps.add(askUserStep("refundReason", "MISSING_REQUIRED_INFORMATION"));
        }

        boolean ready = !isBlank(context.userId())
                && !isBlank(context.orderId())
                && !isBlank(context.orderStatus())
                && !isBlank(context.refundReason());
        return assignStepIds(new RefundPlan(ready, steps, checklist));
    }

    private RefundPlan fallbackPlan(PlanningContext context) {
        List<PlannedStep> steps = new ArrayList<>();
        if (isBlank(context.orderId())) {
            steps.add(askUserStep("orderId", "MISSING_REQUIRED_IDENTITY"));
        }
        if (isBlank(context.refundReason())) {
            steps.add(askUserStep("refundReason", "MISSING_REQUIRED_INFORMATION"));
        }
        List<ChecklistItem> checklist = List.of(
                item("userId", context.userId()),
                item("orderId", context.orderId()),
                item("orderStatus", context.orderStatus()),
                item("refundReason", context.refundReason())
        );
        return assignStepIds(new RefundPlan(false, steps, checklist));
    }

    private PlannedStep askUserStep(String targetField, String reasonForUser) {
        PlannedStep step = new PlannedStep(null, "ASK_USER", targetField, null, null, reasonForUser);
        return new PlannedStep(stepIdOf(step), step.action(), step.targetField(), step.toolName(), step.input(), step.reasonForUser());
    }

    private PlannedStep toolCallStep(String targetField, String toolName, Map<String, Object> input) {
        PlannedStep step = new PlannedStep(null, "TOOL_CALL", targetField, toolName, input, null);
        return new PlannedStep(stepIdOf(step), step.action(), step.targetField(), step.toolName(), step.input(), step.reasonForUser());
    }

    private RefundPlan assignStepIds(RefundPlan plan) {
        if (plan == null || plan.steps() == null) {
            return plan;
        }
        List<PlannedStep> steps = new ArrayList<>(plan.steps().size());
        for (PlannedStep step : plan.steps()) {
            StepId stepId = step.stepId() != null ? step.stepId() : stepIdOf(step);
            steps.add(new PlannedStep(stepId, step.action(), step.targetField(), step.toolName(), step.input(), step.reasonForUser()));
        }
        return new RefundPlan(plan.readyToEvaluate(), steps, plan.checklist());
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
                + "- lastErrorMessage: " + safe(context.lastErrorMessage());
    }

    private String sessionIdFor(PlanningContext context) {
        String sessionId = context.sessionId();
        return sessionId != null && !sessionId.isBlank() ? sessionId : "anonymous";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

}
