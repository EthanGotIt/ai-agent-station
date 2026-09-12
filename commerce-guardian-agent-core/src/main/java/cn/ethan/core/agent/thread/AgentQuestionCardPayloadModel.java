package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;

/**
 * 类型职责：表达公开 QuestionCard 事实，只保留前端恢复所需字段并隔离身份与内部状态。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentQuestionCardPayloadModel(
        String questionId,
        String runId,
        AgentQuestionCardResumeTargetEnum resumeTarget,
        int stepNo,
        long version,
        String title,
        String prompt,
        String fieldsJson
) implements AgentItemPayloadValue {

    public AgentQuestionCardPayloadModel {
        questionId = required(questionId, "questionId");
        runId = normalize(runId);
        resumeTarget = resumeTarget == null ? AgentQuestionCardResumeTargetEnum.AGENT : resumeTarget;
        if (resumeTarget == AgentQuestionCardResumeTargetEnum.WORKFLOW && runId == null) {
            throw new IllegalArgumentException("Workflow QuestionCard must have runId");
        }
        if (stepNo < 0 || version < 0) {
            throw new IllegalArgumentException("stepNo and version must not be negative");
        }
        title = title == null || title.isBlank() ? "需要补充信息" : title.strip();
        prompt = prompt == null ? "" : prompt.strip();
        fieldsJson = fieldsJson == null || fieldsJson.isBlank() ? "[]" : fieldsJson.strip();
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.QUESTION_CARD;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
