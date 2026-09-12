package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextBudgetReport;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.coordination.AgentDecisionTypeEnum;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentItemPayloadModel;
import cn.ethan.core.agent.thread.AgentDecisionPayloadModel;
import cn.ethan.core.agent.thread.AgentOrderActionPayloadModel;
import cn.ethan.core.agent.thread.AgentQuestionAnswerPayloadModel;
import cn.ethan.core.agent.thread.AgentQuestionCardPayloadModel;
import cn.ethan.core.agent.thread.AgentWorkflowCheckpointPayloadModel;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionPayloadModel;
import cn.ethan.core.agent.thread.AgentWorkflowStepPayloadModel;
import cn.ethan.core.agent.thread.AgentToolCallPayloadModel;
import cn.ethan.core.agent.thread.AgentToolResultPayloadModel;
import cn.ethan.core.agent.thread.AgentTurnStatePayloadModel;
import cn.ethan.core.agent.thread.AgentWorkflowResultPayloadModel;
import cn.ethan.core.agent.thread.AgentContinuationPayloadModel;
import cn.ethan.core.agent.thread.AgentErrorPayloadModel;
import cn.ethan.core.agent.thread.AgentExecutionEventPayloadModel;
import cn.ethan.core.agent.thread.AgentExternalActionStatusPayloadModel;
import cn.ethan.core.agent.thread.AgentLogisticsEventPayloadModel;
import cn.ethan.core.agent.thread.AgentLogisticsTimelinePayloadModel;
import cn.ethan.core.agent.thread.AgentOrderDetailPayloadModel;
import cn.ethan.core.agent.thread.AgentOrderListPayloadModel;
import cn.ethan.core.agent.thread.AgentOrderSnapshotPayloadModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;

import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;

import java.util.List;

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

    /** 返回可直接交给 Infrastructure Codec 的订单动作值。 */
    public static AgentOrderActionPayloadModel orderActionValue(AgentOrderActionInput action) {
        return new AgentOrderActionPayloadModel(action.sourceTurnId(), action.orderId(), action.actionType());
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

    /** 返回 Agent 续跑事实的受控值。 */
    public static AgentContinuationPayloadModel continuationValue(AgentContinuationInput input) {
        return new AgentContinuationPayloadModel(input.rootTurnId(), input.parentTurnId(), input.triggerRunId(),
                input.triggerCommandId(), input.triggerStatus(), input.triggerSequence(), input.cycleNo());
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

    /** 返回可直接交给 Infrastructure Codec 的 Agent 决策值。 */
    public static AgentDecisionPayloadModel decisionValue(
            AgentDecisionTypeEnum decision,
            int cycleNo,
            String runId,
            String code,
            boolean correctionAttempt
    ) {
        return new AgentDecisionPayloadModel(decision, cycleNo, runId, code, correctionAttempt);
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

    /** 返回不包含身份和内部状态的 QuestionCard 公开值。 */
    public static AgentQuestionCardPayloadModel questionCardValue(AgentQuestionCardModel question) {
        return new AgentQuestionCardPayloadModel(question.questionId(), question.runId(), question.resumeTarget(),
                question.stepNo(), question.version(), question.title(), question.prompt(), question.fieldsJson());
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

    /** 返回 Workflow 确认所需的受控公开值。 */
    public static AgentWorkflowCheckpointPayloadModel workflowCheckpointValue(
            AgentWorkflowCheckpointModel checkpoint
    ) {
        return new AgentWorkflowCheckpointPayloadModel(checkpoint.checkpointId(), checkpoint.runId(),
                checkpoint.nodeId(), checkpoint.actionType(), checkpoint.orderId(), checkpoint.impactSummary(),
                checkpoint.factsFingerprint(), checkpoint.version());
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

    /** 返回 QuestionCard 回答的受控值。 */
    public static AgentQuestionAnswerPayloadModel questionAnswerValue(AgentQuestionAnswerInput input) {
        return new AgentQuestionAnswerPayloadModel(input.questionId(), input.runId(), input.resumeTarget(),
                input.enqueuedQuestionVersion(), input.action());
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

    /** 返回 Workflow Checkpoint 决策的受控值。 */
    public static AgentWorkflowDecisionPayloadModel workflowDecisionValue(
            cn.ethan.core.agent.thread.AgentWorkflowDecisionInput input
    ) {
        return new AgentWorkflowDecisionPayloadModel(input.runId(), input.checkpointId(), input.expectedVersion(),
                input.decision(), input.factsFingerprint());
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

    /** 返回可直接交给 Infrastructure Codec 的 Workflow 节点值。 */
    public static AgentWorkflowStepPayloadModel workflowStepValue(
            String runId,
            String node,
            String status,
            String branch,
            String code,
            long elapsedMillis
    ) {
        return new AgentWorkflowStepPayloadModel(runId, node, status, branch, code,
                Math.max(0L, elapsedMillis));
    }

    public static String turnState(AgentTurnStatusEnum status, String errorCode) {
        return "{\"status\":\"" + status.name() + "\",\"errorCode\":"
                + (errorCode == null ? "null" : "\"" + escape(errorCode) + "\"") + "}";
    }

    /** 返回可直接交给 Infrastructure Codec 的 Turn 状态值；字符串方法保留给历史调用方。 */
    public static AgentTurnStatePayloadModel turnStateValue(AgentTurnStatusEnum status, String errorCode) {
        return new AgentTurnStatePayloadModel(status, errorCode);
    }

    /** 返回可直接交给 Infrastructure Codec 的 Tool 调用值。 */
    public static AgentToolCallPayloadModel toolCallValue(
            String tool, String invocationId, String toolBatchId, java.util.Map<String, String> arguments
    ) {
        return new AgentToolCallPayloadModel(tool, invocationId, toolBatchId, arguments);
    }

    /** 返回可直接交给 Infrastructure Codec 的 Tool 结果值。 */
    public static AgentToolResultPayloadModel toolResultValue(
            String tool, String invocationId, String toolBatchId, String status, String result, boolean truncated
    ) {
        return new AgentToolResultPayloadModel(tool, invocationId, toolBatchId, status, result, truncated);
    }

    /** 返回可直接交给 Infrastructure Codec 的 Workflow 结果值。 */
    public static AgentWorkflowResultPayloadModel workflowResultValue(
            String runId, String status, String message
    ) {
        return new AgentWorkflowResultPayloadModel(runId, status, message);
    }

    /** 返回上下文组装事件的受控值。 */
    public static AgentExecutionEventPayloadModel executionEventValue(AgentContextBudgetReport report) {
        return new AgentExecutionEventPayloadModel("CONTEXT_ASSEMBLED", report.estimatedTokens(),
                report.inputBudget(), report.snapshotThroughSequence(), report.compressed(), report.degraded(),
                report.droppedItems(), report.readWatermark(), report.coveredThroughSequence(),
                report.readItemCount(), report.historyComplete(), report.peakEstimatedTokens(),
                report.pressurePrunedToolResults());
    }

    /** 返回受控错误事实。 */
    public static AgentErrorPayloadModel errorValue(String code) {
        return new AgentErrorPayloadModel(code, null);
    }

    /** 返回带用户可见说明的受控错误事实。 */
    public static AgentErrorPayloadModel errorValue(String code, String message) {
        return new AgentErrorPayloadModel(code, message);
    }

    /** 返回外部动作状态快照，供 Workflow 和 Worker 共用同一数据边界。 */
    public static AgentExternalActionStatusPayloadModel externalActionStatusValue(
            String commandId,
            String runId,
            String status,
            int attemptCount,
            int retryCycleAttemptCount,
            int maxAttempts,
            String actionType,
            String orderId,
            String nextAttemptAt,
            String code,
            String message,
            String verificationStatus,
            String verificationMessage,
            String verifiedAt
    ) {
        return new AgentExternalActionStatusPayloadModel(commandId, runId, status, attemptCount,
                retryCycleAttemptCount, maxAttempts, actionType, orderId, nextAttemptAt, code, message,
                verificationStatus, verificationMessage, verifiedAt);
    }

    /** 将订单领域快照转换为不含身份信息的 Item 值。 */
    public static AgentOrderSnapshotPayloadModel orderSnapshotValue(OrderSnapshotModel order) {
        if (order == null) {
            throw new IllegalArgumentException("订单事实不能为空");
        }
        return new AgentOrderSnapshotPayloadModel(order.orderId(), order.status().name(),
                order.daysSinceDelivery(), string(order.createdAt()), string(order.expectedDeliveryAt()),
                string(order.lastLogisticsAt()), order.logisticsStatus(), order.paidAmount(), order.currency(),
                order.itemSummary(), order.hiddenAt() == null ? "ACTIVE" : "HIDDEN");
    }

    /** 返回订单列表事实的受控值。 */
    public static AgentOrderListPayloadModel orderListValue(List<OrderSnapshotModel> orders) {
        return new AgentOrderListPayloadModel("SUCCESS", orders == null ? List.of()
                : orders.stream().filter(java.util.Objects::nonNull)
                .map(AgentTurnItemPayloads::orderSnapshotValue).toList());
    }

    /** 返回订单详情事实的受控值。 */
    public static AgentOrderDetailPayloadModel orderDetailValue(OrderSnapshotModel order) {
        AgentOrderSnapshotPayloadModel value = orderSnapshotValue(order);
        return new AgentOrderDetailPayloadModel(value.orderId(), value.status(), value.daysSinceDelivery(),
                value.createdAt(), value.expectedDeliveryAt(), value.lastLogisticsAt(), value.logisticsStatus(),
                value.paidAmount(), value.currency(), value.itemSummary(), value.visibility());
    }

    /** 返回物流时间线事实的受控值。 */
    public static AgentLogisticsTimelinePayloadModel logisticsTimelineValue(
            String orderId, List<LogisticsEventModel> events
    ) {
        List<AgentLogisticsEventPayloadModel> values = events == null ? List.of() : events.stream()
                .filter(java.util.Objects::nonNull)
                .map(event -> new AgentLogisticsEventPayloadModel(event.eventId(), event.status(), event.location(),
                        event.description(), string(event.occurredAt())))
                .toList();
        return new AgentLogisticsTimelinePayloadModel(orderId, values);
    }

    public static String context(AgentContextBudgetReport report) {
        return "{\"kind\":\"CONTEXT_ASSEMBLED\",\"estimatedTokens\":" + report.estimatedTokens()
                + ",\"inputBudget\":" + report.inputBudget()
                + ",\"snapshotThroughSequence\":" + report.snapshotThroughSequence()
                + ",\"compressed\":" + report.compressed()
                + ",\"degraded\":" + report.degraded()
                + ",\"droppedItems\":" + report.droppedItems()
                + ",\"readWatermark\":" + report.readWatermark()
                + ",\"coveredThroughSequence\":" + report.coveredThroughSequence()
                + ",\"readItemCount\":" + report.readItemCount()
                + ",\"historyComplete\":" + report.historyComplete()
                + ",\"peakEstimatedTokens\":" + report.peakEstimatedTokens()
                + ",\"pressurePrunedToolResults\":" + report.pressurePrunedToolResults() + "}";
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
        return AgentItemPayloadModel.escapeJson(value);
    }

    private static String quotedOrNull(String value) {
        return value == null || value.isBlank() ? "null" : "\"" + escape(value) + "\"";
    }

    private static String string(java.time.Instant value) {
        return value == null ? null : value.toString();
    }
}
