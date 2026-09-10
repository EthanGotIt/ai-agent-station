package cn.ethan.core.agent.workflow;

import java.time.Instant;

/**
 * 类型职责：保存一个确定性 Workflow 的可恢复状态、乐观版本和不可变编排归属。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentWorkflowRunModel(
        String runId,
        String threadId,
        String turnId,
        String userId,
        AgentWorkflowTypeEnum workflowType,
        AgentWorkflowStatusEnum status,
        long version,
        String stepsJson,
        String stateJson,
        Instant createdAt,
        Instant updatedAt,
        AgentWorkflowOrchestrationVersionEnum orchestrationVersion
) {
    public AgentWorkflowRunModel {
        if (runId == null || runId.isBlank() || threadId == null || threadId.isBlank()
                || userId == null || userId.isBlank() || workflowType == null
                || orchestrationVersion == null) {
            throw new IllegalArgumentException("WorkflowRun identity must not be blank");
        }
        if (status == null || version < 0) {
            throw new IllegalArgumentException("WorkflowRun 状态和版本必须有效");
        }
        stepsJson = stepsJson == null || stepsJson.isBlank() ? "[]" : stepsJson;
        stateJson = stateJson == null || stateJson.isBlank() ? "{}" : stateJson;
    }

    /** 保留旧持久化调用边界；没有步骤快照的历史 Run 使用安全空结构。 */
    public AgentWorkflowRunModel(
            String runId,
            String threadId,
            String turnId,
            String userId,
            AgentWorkflowTypeEnum workflowType,
            AgentWorkflowStatusEnum status,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(runId, threadId, turnId, userId, workflowType, status, version,
                "[]", "{}", createdAt, updatedAt,
                AgentWorkflowOrchestrationVersionEnum.LEGACY_V1);
    }

    /** 保留没有编排版本列的历史调用边界，历史 Run 按 LEGACY_V1 恢复。 */
    public AgentWorkflowRunModel(
            String runId,
            String threadId,
            String turnId,
            String userId,
            AgentWorkflowTypeEnum workflowType,
            AgentWorkflowStatusEnum status,
            long version,
            String stepsJson,
            String stateJson,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(runId, threadId, turnId, userId, workflowType, status, version,
                stepsJson, stateJson, createdAt, updatedAt,
                AgentWorkflowOrchestrationVersionEnum.LEGACY_V1);
    }

    public AgentWorkflowRunModel status(AgentWorkflowStatusEnum nextStatus, Instant now) {
        return status(nextStatus, stepsJson, stateJson, now);
    }

    /** 在状态转换时同步写入可恢复步骤和业务状态，避免状态与进度快照跨版本。 */
    public AgentWorkflowRunModel status(
            AgentWorkflowStatusEnum nextStatus,
            String nextStepsJson,
            String nextStateJson,
            Instant now
    ) {
        if (nextStatus == null || now == null) {
            throw new IllegalArgumentException("WorkflowRun 状态转换必须提供目标状态和时间");
        }
        if (isImmutableTerminal(status)) {
            throw new IllegalStateException("WorkflowRun 不允许从不可变终态转换：" + status);
        }
        if (nextStatus == status || !isAllowed(status, nextStatus)) {
            throw new IllegalStateException("WorkflowRun 不允许状态转换：" + status + " -> " + nextStatus);
        }
        return new AgentWorkflowRunModel(runId, threadId, turnId, userId, workflowType,
                nextStatus, version + 1, nextStepsJson, nextStateJson, createdAt, now,
                orchestrationVersion);
    }

    /** 在不改变状态的本地事务内推进 Workflow 步骤和可恢复业务状态。 */
    public AgentWorkflowRunModel progress(String nextStepsJson, String nextStateJson, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("WorkflowRun 进度转换必须提供时间");
        }
        if (isImmutableTerminal(status)) {
            throw new IllegalStateException("WorkflowRun 终态不能更新步骤：" + status);
        }
        return new AgentWorkflowRunModel(runId, threadId, turnId, userId, workflowType,
                status, version + 1, nextStepsJson, nextStateJson, createdAt, now,
                orchestrationVersion);
    }

    private static boolean isAllowed(AgentWorkflowStatusEnum current, AgentWorkflowStatusEnum next) {
        return switch (current) {
            case WAITING_USER_INPUT -> next == AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION
                    || next == AgentWorkflowStatusEnum.REJECTED
                    || next == AgentWorkflowStatusEnum.FAILED;
            case WAITING_EXTERNAL_ACTION -> next == AgentWorkflowStatusEnum.COMPLETED
                    || next == AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED
                    || next == AgentWorkflowStatusEnum.FAILED;
            case MANUAL_RETRY_REQUIRED -> next == AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION
                    || next == AgentWorkflowStatusEnum.COMPLETED;
            case COMPLETED, REJECTED, FAILED -> false;
        };
    }

    private static boolean isImmutableTerminal(AgentWorkflowStatusEnum value) {
        return value == AgentWorkflowStatusEnum.COMPLETED
                || value == AgentWorkflowStatusEnum.REJECTED
                || value == AgentWorkflowStatusEnum.FAILED;
    }
}
