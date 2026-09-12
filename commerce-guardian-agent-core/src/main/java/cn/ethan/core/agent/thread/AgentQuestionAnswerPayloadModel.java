package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;

/**
 * 类型职责：表达 QuestionCard 回答事实，避免把回答内容和用户请求上下文写入 Item。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentQuestionAnswerPayloadModel(
        String questionId,
        String runId,
        AgentQuestionCardResumeTargetEnum resumeTarget,
        long enqueuedQuestionVersion,
        AgentQuestionCardAnswerActionEnum action
) implements AgentItemPayloadValue {

    public AgentQuestionAnswerPayloadModel {
        questionId = required(questionId, "questionId");
        runId = normalize(runId);
        resumeTarget = resumeTarget == null ? AgentQuestionCardResumeTargetEnum.AGENT : resumeTarget;
        if (enqueuedQuestionVersion < 0 || action == null) {
            throw new IllegalArgumentException("answer version and action must be valid");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.QUESTION_ANSWER;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
