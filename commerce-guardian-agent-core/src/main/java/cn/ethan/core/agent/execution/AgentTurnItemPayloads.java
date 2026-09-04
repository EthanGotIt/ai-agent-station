package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextBudgetReport;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.coordination.AgentDecisionTypeEnum;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentItemPayloadModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;

/**
 * 类型职责：生成 Runtime 写入的受控 Item payload，并保持序号回填不带业务副作用。
 *
 * @author ethan
 * @date 2026-08-24
 */
public final class AgentTurnItemPayloads {

    private AgentTurnItemPayloads() {
    }

    public static String orderAction(AgentOrderActionInput action) {
        return "{\"sourceTurnId\":\"" + escape(action.sourceTurnId())
                + "\",\"orderId\":\"" + escape(action.orderId())
                + "\",\"actionType\":\"" + action.actionType().name() + "\"}";
    }

    public static String continuation(AgentContinuationInput input) {
        return "{\"rootTurnId\":\"" + escape(input.rootTurnId())
                + "\",\"parentTurnId\":\"" + escape(input.parentTurnId())
                + "\",\"triggerRunId\":\"" + escape(input.triggerRunId())
                + "\",\"triggerCommandId\":"
                + (input.triggerCommandId() == null ? "null" : "\"" + escape(input.triggerCommandId()) + "\"")
                + ",\"triggerStatus\":\"" + escape(input.triggerStatus())
                + "\",\"triggerSequence\":" + input.triggerSequence()
                + ",\"cycleNo\":" + input.cycleNo() + "}";
    }

    public static String decision(
            AgentDecisionTypeEnum decision,
            int cycleNo,
            String runId,
            String code
    ) {
        return decision(decision, cycleNo, runId, code, false);
    }

    /** 生成 Agent 终止决策事实；纠正调用只在需要时显式标记。 */
    public static String decision(
            AgentDecisionTypeEnum decision,
            int cycleNo,
            String runId,
            String code,
            boolean correctionAttempt
    ) {
        return "{\"decision\":\"" + decision.name()
                + "\",\"cycleNo\":" + cycleNo
                + ",\"runId\":" + quotedOrNull(runId)
                + ",\"code\":" + quotedOrNull(code)
                + (correctionAttempt ? ",\"correctionAttempt\":true" : "") + "}";
    }

    /** 生成只包含受控字段的 QuestionCard 事实。 */
    public static String questionCard(AgentQuestionCardModel question) {
        String data = "{\"questionId\":" + quotedOrNull(question.questionId())
                + ",\"runId\":" + quotedOrNull(question.runId())
                + ",\"resumeTarget\":\"" + question.resumeTarget().name()
                + "\",\"stepNo\":" + question.stepNo()
                + ",\"version\":" + question.version()
                + ",\"title\":" + quotedOrNull(question.title())
                + ",\"prompt\":" + quotedOrNull(question.prompt())
                + ",\"fieldsJson\":" + quotedOrNull(question.fieldsJson()) + "}";
        return AgentItemPayloadModel.ensure(AgentItemTypeEnum.QUESTION_CARD, data);
    }

    /** 生成固定 Workflow 人工执行确认事实；不把确认当成 QuestionCard 授权字段。 */
    public static String workflowCheckpoint(AgentWorkflowCheckpointModel checkpoint) {
        String data = "{\"checkpointId\":" + quotedOrNull(checkpoint.checkpointId())
                + ",\"runId\":" + quotedOrNull(checkpoint.runId())
                + ",\"nodeId\":" + quotedOrNull(checkpoint.nodeId())
                + ",\"actionType\":" + quotedOrNull(checkpoint.actionType())
                + ",\"orderId\":" + quotedOrNull(checkpoint.orderId())
                + ",\"impactSummary\":" + quotedOrNull(checkpoint.impactSummary())
                + ",\"factsFingerprint\":" + quotedOrNull(checkpoint.factsFingerprint())
                + ",\"version\":" + checkpoint.version() + "}";
        return AgentItemPayloadModel.ensure(AgentItemTypeEnum.WORKFLOW_CHECKPOINT, data);
    }

    /** 生成 QuestionCard 回答事实，不暴露原始请求上下文。 */
    public static String questionAnswer(AgentQuestionAnswerInput input) {
        String data = "{\"questionId\":" + quotedOrNull(input.questionId())
                + ",\"runId\":" + quotedOrNull(input.runId())
                + ",\"resumeTarget\":\"" + input.resumeTarget().name()
                + "\",\"enqueuedQuestionVersion\":" + input.enqueuedQuestionVersion()
                + ",\"action\":\"" + input.action().name() + "\"}";
        return AgentItemPayloadModel.ensure(AgentItemTypeEnum.QUESTION_ANSWER, data);
    }

    /** 生成 Workflow Checkpoint 决策事实，只保留批准/拒绝和版本指纹。 */
    public static String workflowDecision(
            cn.ethan.core.agent.thread.AgentWorkflowDecisionInput input
    ) {
        String data = "{\"runId\":" + quotedOrNull(input.runId())
                + ",\"checkpointId\":" + quotedOrNull(input.checkpointId())
                + ",\"expectedVersion\":" + input.expectedVersion()
                + ",\"decision\":\"" + input.decision().name()
                + "\",\"factsFingerprint\":" + quotedOrNull(input.factsFingerprint()) + "}";
        return AgentItemPayloadModel.ensure(AgentItemTypeEnum.WORKFLOW_DECISION, data);
    }

    public static String workflowStep(
            String runId,
            String node,
            String status,
            String branch,
            String code,
            long elapsedMillis
    ) {
        return "{\"runId\":" + quotedOrNull(runId)
                + ",\"node\":\"" + escape(node)
                + "\",\"status\":\"" + escape(status)
                + "\",\"branch\":" + quotedOrNull(branch)
                + ",\"code\":" + quotedOrNull(code)
                + ",\"elapsedMillis\":" + Math.max(0L, elapsedMillis) + "}";
    }

    public static String turnState(AgentTurnStatusEnum status, String errorCode) {
        return "{\"status\":\"" + status.name() + "\",\"errorCode\":"
                + (errorCode == null ? "null" : "\"" + escape(errorCode) + "\"") + "}";
    }

    public static String context(AgentContextBudgetReport report) {
        return "{\"kind\":\"CONTEXT_ASSEMBLED\",\"estimatedTokens\":" + report.estimatedTokens()
                + ",\"inputBudget\":" + report.inputBudget()
                + ",\"snapshotThroughSequence\":" + report.snapshotThroughSequence()
                + ",\"compressed\":" + report.compressed()
                + ",\"degraded\":" + report.degraded()
                + ",\"droppedItems\":" + report.droppedItems() + "}";
    }

    public static AgentItemModel withSequence(AgentItemModel item, long sequence) {
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt());
    }

    public static AgentItemTypeEnum parseType(String value) {
        try {
            return AgentItemTypeEnum.valueOf(value);
        } catch (RuntimeException failure) {
            return AgentItemTypeEnum.EXECUTION_EVENT;
        }
    }

    public static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String quotedOrNull(String value) {
        return value == null || value.isBlank() ? "null" : "\"" + escape(value) + "\"";
    }
}
