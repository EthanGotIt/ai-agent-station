package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：返回 Workflow Checkpoint 决策 admission 创建的 Turn 和首个持久事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentWorkflowDecisionAdmissionResult(
        AgentTurnModel turn,
        AgentItemModel initialItem,
        boolean newlyAdmitted
) {

    public AgentWorkflowDecisionAdmissionResult {
        if (turn == null || turn.workflowDecisionInput() == null
                || turn.inputKind() != cn.ethan.core.agent.thread.AgentTurnInputKindEnum.WORKFLOW_DECISION) {
            throw new IllegalArgumentException("Workflow decision admission 结果必须包含 WORKFLOW_DECISION Turn");
        }
        if (newlyAdmitted && (initialItem == null || initialItem.sequence() < 1)) {
            throw new IllegalArgumentException("新 Workflow decision admission 必须返回已分配 Sequence 的首个 Item");
        }
        if (!newlyAdmitted && initialItem != null) {
            throw new IllegalArgumentException("幂等 Workflow decision admission 不得重复发布首个 Item");
        }
    }
}
