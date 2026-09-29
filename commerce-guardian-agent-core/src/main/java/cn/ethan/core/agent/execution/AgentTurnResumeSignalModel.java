package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;

import java.time.Instant;

/**
 * 类型职责：记录恢复既有 Turn 的一次独立输入或动作回执，并以请求身份实现持久化幂等。
 *
 * @author ethan
 * @date 2026-09-29
 */
public record AgentTurnResumeSignalModel(
        String signalId,
        String turnId,
        String userId,
        String requestId,
        SignalKindEnum kind,
        String interactionId,
        long expectedInteractionVersion,
        AgentQuestionAnswerInput questionAnswerInput,
        AgentWorkflowDecisionInput workflowDecisionInput,
        String payloadJson,
        String itemId,
        SignalStatusEnum status,
        long version,
        Instant createdAt,
        Instant appliedAt
) {
    public AgentTurnResumeSignalModel {
        signalId = required(signalId, "signalId", 64);
        turnId = required(turnId, "turnId", 64);
        userId = required(userId, "userId", 128);
        requestId = required(requestId, "requestId", 128);
        if (kind == null || expectedInteractionVersion < 0 || version < 0) {
            throw new IllegalArgumentException("Turn 恢复信号类型或版本无效");
        }
        interactionId = interactionId == null || interactionId.isBlank()
                ? null : required(interactionId, "interactionId", 128);
        payloadJson = payloadJson == null || payloadJson.isBlank() ? null : payloadJson;
        if (payloadJson != null && payloadJson.length() > 32_768) {
            throw new IllegalArgumentException("Turn 恢复输入超过持久化上限");
        }
        boolean questionSignal = kind == SignalKindEnum.QUESTION_ANSWER;
        boolean decisionSignal = kind == SignalKindEnum.WORKFLOW_DECISION;
        if (questionSignal != (questionAnswerInput != null)
                || decisionSignal != (workflowDecisionInput != null)
                || (questionSignal || decisionSignal) == (payloadJson != null)) {
            throw new IllegalArgumentException("Turn 恢复信号的类型化输入与载荷不匹配");
        }
        if (questionAnswerInput != null
                && (!questionAnswerInput.questionId().equals(interactionId)
                || questionAnswerInput.admissionExpectedVersion() != expectedInteractionVersion)) {
            throw new IllegalArgumentException("QuestionCard 恢复信号与交互身份或版本不一致");
        }
        if (workflowDecisionInput != null
                && (!workflowDecisionInput.checkpointId().equals(interactionId)
                || workflowDecisionInput.expectedVersion() != expectedInteractionVersion)) {
            throw new IllegalArgumentException("Checkpoint 恢复信号与交互身份或版本不一致");
        }
        itemId = required(itemId, "itemId", 64);
        status = status == null ? SignalStatusEnum.PENDING : status;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        if ((status == SignalStatusEnum.APPLIED) != (appliedAt != null)) {
            throw new IllegalArgumentException("恢复信号状态与应用时间不一致");
        }
    }

    public AgentTurnResumeSignalModel applied(Instant at) {
        if (status != SignalStatusEnum.PENDING || at == null) {
            throw new IllegalStateException("只有待处理恢复信号可以收敛为已应用");
        }
        return new AgentTurnResumeSignalModel(signalId, turnId, userId, requestId, kind, interactionId,
                expectedInteractionVersion, questionAnswerInput, workflowDecisionInput, payloadJson, itemId,
                SignalStatusEnum.APPLIED, version + 1, createdAt, at);
    }

    private static String required(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    public enum SignalKindEnum {
        QUESTION_ANSWER,
        WORKFLOW_DECISION,
        STEER,
        COMMAND_RESULT
    }

    public enum SignalStatusEnum {
        PENDING,
        APPLIED
    }
}
