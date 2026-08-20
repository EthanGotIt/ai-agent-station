package cn.ethan.core.agent.workflow;

import java.time.Instant;

/**
 * 持久化 QuestionCard 检查点。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentWorkflowQuestionModel(
        String runId,
        String threadId,
        String turnId,
        String userId,
        String questionId,
        String checkpointId,
        long version,
        String title,
        String prompt,
        String fieldsJson,
        AgentWorkflowQuestionStatusEnum status,
        Instant createdAt,
        Instant answeredAt
) {
    public AgentWorkflowQuestionModel {
        if (runId == null || runId.isBlank() || threadId == null || threadId.isBlank()
                || questionId == null || questionId.isBlank()) {
            throw new IllegalArgumentException("question identity must not be blank");
        }
        title = title == null ? "需要确认" : title;
        prompt = prompt == null ? "" : prompt;
        fieldsJson = fieldsJson == null ? "[]" : fieldsJson;
        status = status == null ? AgentWorkflowQuestionStatusEnum.OPEN : status;
    }

    public AgentWorkflowQuestionModel answered(Instant at) {
        return new AgentWorkflowQuestionModel(runId, threadId, turnId, userId, questionId,
                checkpointId, version + 1, title, prompt, fieldsJson,
                AgentWorkflowQuestionStatusEnum.ANSWERED, createdAt, at);
    }
}
