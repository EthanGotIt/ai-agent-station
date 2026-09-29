package cn.ethan.core.agent.workflow;

import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：描述启动恢复时需要把 Workflow 所属 Turn 与 Run 状态重新对齐的事实。
 *
 * @author ethan
 * @date 2026-08-22
 */
public record AgentWorkflowTaskOwnerRecoveryCandidate(
        AgentTurnModel turn,
        AgentWorkflowStatusEnum workflowStatus,
        boolean hasOpenInteraction
) {

    public AgentWorkflowTaskOwnerRecoveryCandidate {
        if (turn == null || turn.workflowTaskId() == null || turn.workflowTaskId().isBlank()) {
            throw new IllegalArgumentException("Workflow owner recovery 必须绑定 WorkflowTask");
        }
        if (workflowStatus == null) {
            throw new IllegalArgumentException("Workflow owner recovery 必须具有 Run 状态");
        }
    }
}
