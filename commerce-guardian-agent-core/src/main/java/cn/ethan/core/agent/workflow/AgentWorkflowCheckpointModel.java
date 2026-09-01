package cn.ethan.core.agent.workflow;

import java.time.Instant;

/**
 * 类型职责：保存固定 Workflow 的人工执行确认及其事实指纹。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentWorkflowCheckpointModel(
        String checkpointId,
        String runId,
        String threadId,
        String turnId,
        String userId,
        String nodeId,
        String actionType,
        String orderId,
        String impactSummary,
        String factsFingerprint,
        long version,
        AgentWorkflowCheckpointStatusEnum status,
        AgentWorkflowDecisionEnum decision,
        Instant createdAt,
        Instant decidedAt
) {

    public AgentWorkflowCheckpointModel {
        checkpointId = identity(checkpointId, "checkpointId");
        runId = identity(runId, "runId");
        threadId = identity(threadId, "threadId");
        turnId = identity(turnId, "turnId");
        userId = identity(userId, "userId");
        nodeId = identity(nodeId, "nodeId");
        actionType = identity(actionType, "actionType");
        orderId = identity(orderId, "orderId");
        impactSummary = impactSummary == null ? "" : impactSummary.trim();
        factsFingerprint = identity(factsFingerprint, "factsFingerprint");
        if (version < 0 || createdAt == null || status == null) {
            throw new IllegalArgumentException("Workflow Checkpoint version、状态和时间必须有效");
        }
        if (status == AgentWorkflowCheckpointStatusEnum.OPEN && (decision != null || decidedAt != null)) {
            throw new IllegalArgumentException("开放 Checkpoint 不能具有决策事实");
        }
        if (status == AgentWorkflowCheckpointStatusEnum.APPROVED
                && decision != AgentWorkflowDecisionEnum.APPROVE) {
            throw new IllegalArgumentException("批准 Checkpoint 必须关联 APPROVE");
        }
        if (status == AgentWorkflowCheckpointStatusEnum.REJECTED
                && decision != AgentWorkflowDecisionEnum.REJECT) {
            throw new IllegalArgumentException("拒绝 Checkpoint 必须关联 REJECT");
        }
        if (status != AgentWorkflowCheckpointStatusEnum.OPEN && decidedAt == null) {
            throw new IllegalArgumentException("已结束 Checkpoint 必须具有决策时间");
        }
    }

    public AgentWorkflowCheckpointModel approve(Instant at) {
        return decide(AgentWorkflowDecisionEnum.APPROVE, AgentWorkflowCheckpointStatusEnum.APPROVED, at);
    }

    public AgentWorkflowCheckpointModel reject(Instant at) {
        return decide(AgentWorkflowDecisionEnum.REJECT, AgentWorkflowCheckpointStatusEnum.REJECTED, at);
    }

    public AgentWorkflowCheckpointModel supersede(Instant at) {
        if ((status != AgentWorkflowCheckpointStatusEnum.OPEN
                && status != AgentWorkflowCheckpointStatusEnum.APPROVED) || at == null) {
            throw new IllegalStateException("只有开放或已批准 Checkpoint 可以标记 SUPERSEDED");
        }
        return new AgentWorkflowCheckpointModel(checkpointId, runId, threadId, turnId, userId, nodeId,
                actionType, orderId, impactSummary, factsFingerprint, version + 1,
                AgentWorkflowCheckpointStatusEnum.SUPERSEDED, null, createdAt, at);
    }

    private AgentWorkflowCheckpointModel decide(AgentWorkflowDecisionEnum nextDecision,
                                                 AgentWorkflowCheckpointStatusEnum nextStatus, Instant at) {
        if (status != AgentWorkflowCheckpointStatusEnum.OPEN || at == null) {
            throw new IllegalStateException("Workflow Checkpoint 当前不可决策");
        }
        return new AgentWorkflowCheckpointModel(checkpointId, runId, threadId, turnId, userId, nodeId,
                actionType, orderId, impactSummary, factsFingerprint, version + 1, nextStatus,
                nextDecision, createdAt, at);
    }

    private static String identity(String value, String name) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
