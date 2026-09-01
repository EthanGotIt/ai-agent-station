package cn.ethan.core.agent.workflow;

import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：描述启动恢复时需要把 Workflow 所属 Turn 与 Run 状态重新对齐的事实。
 *
 * @author ethan
 * @date 2026-08-22
 */
public record AgentWorkflowOwnerRecoveryCandidate(
        AgentTurnModel turn,
        AgentWorkflowStatusEnum workflowStatus,
        boolean hasOpenInteraction
) {

    public AgentWorkflowOwnerRecoveryCandidate {
        if (turn == null || turn.workflowRunId() == null || turn.workflowRunId().isBlank()) {
            throw new IllegalArgumentException("Workflow owner recovery 必须绑定 WorkflowRun");
        }
        if (workflowStatus == null) {
            throw new IllegalArgumentException("Workflow owner recovery 必须具有 Run 状态");
        }
    }
}
