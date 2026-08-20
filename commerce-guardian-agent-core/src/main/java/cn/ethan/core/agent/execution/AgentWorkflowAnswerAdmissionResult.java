package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：返回 Workflow 回答 admission 的持久 Turn、真实 Question 版本和首个事实。
 *
 * @author ethan
 * @date 2026-08-21
 */
public record AgentWorkflowAnswerAdmissionResult(
        AgentTurnModel turn,
        long enqueuedQuestionVersion,
        AgentItemModel initialItem,
        boolean newlyAdmitted
) {

    public AgentWorkflowAnswerAdmissionResult {
        if (turn == null || turn.workflowAnswerInput() == null
                || enqueuedQuestionVersion != turn.workflowAnswerInput().enqueuedQuestionVersion()) {
            throw new IllegalArgumentException("admission 结果必须包含版本一致的 Workflow 回答 Turn");
        }
        if (newlyAdmitted && (initialItem == null || initialItem.sequence() < 1)) {
            throw new IllegalArgumentException("新 admission 必须返回已分配 Sequence 的首个 Item");
        }
        if (!newlyAdmitted && initialItem != null) {
            throw new IllegalArgumentException("幂等 admission 不得重复发布首个 Item");
        }
    }
}
