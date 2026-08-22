package cn.ethan.app.agent.api;

import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;

/**
 * 类型职责：表达 Thread 当前开放 QuestionCard 的可恢复快照，不暴露持久化实体。
 *
 * @author ethan
 * @date 2026-08-23
 */
public record AgentWorkflowQuestionSnapshotDto(
        String runId,
        String threadId,
        String questionId,
        String checkpointId,
        int stepNo,
        long version,
        String title,
        String prompt,
        String fieldsJson
) {
    public static AgentWorkflowQuestionSnapshotDto from(AgentWorkflowQuestionModel question) {
        return new AgentWorkflowQuestionSnapshotDto(
                question.runId(), question.threadId(), question.questionId(), question.checkpointId(),
                question.stepNo(), question.version(), question.title(), question.prompt(), question.fieldsJson());
    }
}
