package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

/**
 * 类型职责：返回 QuestionCard 回答 admission 创建的 Turn 和首个持久事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentQuestionAnswerAdmissionResult(
        AgentTurnModel turn,
        AgentItemModel initialItem,
        boolean newlyAdmitted
) {

    public AgentQuestionAnswerAdmissionResult {
        if (turn == null || turn.questionAnswerInput() == null
                || turn.inputKind() != cn.ethan.core.agent.thread.AgentTurnInputKindEnum.QUESTION_ANSWER) {
            throw new IllegalArgumentException("QuestionCard admission 结果必须包含 QUESTION_ANSWER Turn");
        }
        if (newlyAdmitted && (initialItem == null || initialItem.sequence() < 1)) {
            throw new IllegalArgumentException("新 QuestionCard admission 必须返回已分配 Sequence 的首个 Item");
        }
        if (!newlyAdmitted && initialItem != null) {
            throw new IllegalArgumentException("幂等 QuestionCard admission 不得重复发布首个 Item");
        }
    }
}
