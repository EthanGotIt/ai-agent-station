package cn.ethan.core.agent.workflow;

import java.time.Instant;

/**
 * 类型职责：保存一个确定性 Workflow 的可恢复状态和版本。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentWorkflowRunModel(
        String runId,
        String threadId,
        String turnId,
        String userId,
        String workflowType,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
    public AgentWorkflowRunModel {
        if (runId == null || runId.isBlank() || threadId == null || threadId.isBlank()
                || userId == null || userId.isBlank() || workflowType == null || workflowType.isBlank()) {
            throw new IllegalArgumentException("WorkflowRun identity must not be blank");
        }
        status = status == null ? "WAITING_USER_INPUT" : status;
        version = Math.max(0, version);
    }

    public AgentWorkflowRunModel status(String nextStatus, Instant now) {
        return new AgentWorkflowRunModel(runId, threadId, turnId, userId, workflowType,
                nextStatus, version + 1, createdAt, now);
    }
}
