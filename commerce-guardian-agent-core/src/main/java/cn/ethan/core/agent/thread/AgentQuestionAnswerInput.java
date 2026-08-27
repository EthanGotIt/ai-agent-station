package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;

import java.util.Map;

/**
 * 类型职责：保存 QuestionCard 回答 Turn 的结构化输入，并记录回答后恢复的执行目标。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentQuestionAnswerInput(
        String questionId,
        String runId,
        AgentQuestionCardResumeTargetEnum resumeTarget,
        long enqueuedQuestionVersion,
        Map<String, String> answers,
        AgentQuestionCardAnswerActionEnum action
) {

    public AgentQuestionAnswerInput {
        if (questionId == null || questionId.isBlank() || enqueuedQuestionVersion < 2) {
            throw new IllegalArgumentException("QuestionCard 回答输入标识或入队版本无效");
        }
        questionId = questionId.trim();
        if (runId != null) {
            runId = runId.isBlank() ? null : runId.trim();
        }
        resumeTarget = resumeTarget == null ? AgentQuestionCardResumeTargetEnum.AGENT : resumeTarget;
        if (resumeTarget == AgentQuestionCardResumeTargetEnum.WORKFLOW && runId == null) {
            throw new IllegalArgumentException("Workflow QuestionCard 回答必须关联 runId");
        }
        action = action == null ? AgentQuestionCardAnswerActionEnum.SUBMIT : action;
        answers = answers == null ? Map.of() : Map.copyOf(answers);
        if (action == AgentQuestionCardAnswerActionEnum.SUBMIT && answers.isEmpty()) {
            throw new IllegalArgumentException("QuestionCard 提交回答不能为空");
        }
        if (action == AgentQuestionCardAnswerActionEnum.CANCEL && !answers.isEmpty()) {
            answers = Map.of();
        }
    }

    /** 返回客户端提交时 QuestionCard 的 AVAILABLE 版本。 */
    public long admissionExpectedVersion() {
        return enqueuedQuestionVersion - 2;
    }
}
