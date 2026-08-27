package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentInteractionTypeEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;

/**
 * 类型职责：以一个受控联合快照表达 Thread 当前唯一开放的 QuestionCard 或 Workflow Checkpoint。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentThreadInteractionDto(
        AgentInteractionTypeEnum type,
        String interactionId,
        String threadId,
        String runId,
        String turnId,
        String status,
        long version,
        String resumeTarget,
        String title,
        String prompt,
        String fieldsJson,
        String nodeId,
        String actionType,
        String orderId,
        String impactSummary,
        String factsFingerprint,
        String decision
) {

    public static AgentThreadInteractionDto from(AgentQuestionCardModel question) {
        return new AgentThreadInteractionDto(
                AgentInteractionTypeEnum.QUESTION_CARD, question.questionId(), question.threadId(), question.runId(),
                question.turnId(), question.status().name(), question.version(), question.resumeTarget().name(),
                question.title(), question.prompt(), question.fieldsJson(), null, null, null, null, null, null);
    }

    public static AgentThreadInteractionDto from(AgentWorkflowCheckpointModel checkpoint) {
        return new AgentThreadInteractionDto(
                AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT, checkpoint.checkpointId(), checkpoint.threadId(),
                checkpoint.runId(), checkpoint.turnId(), checkpoint.status().name(), checkpoint.version(), null,
                null, null, null, checkpoint.nodeId(), checkpoint.actionType(), checkpoint.orderId(),
                checkpoint.impactSummary(), checkpoint.factsFingerprint(),
                checkpoint.decision() == null ? null : checkpoint.decision().name());
    }

    public static AgentThreadInteractionDto fromLegacyQuestion(AgentWorkflowQuestionSnapshotDto question) {
        return new AgentThreadInteractionDto(
                AgentInteractionTypeEnum.QUESTION_CARD, question.questionId(), question.threadId(), question.runId(),
                null, "OPEN", question.version(), "WORKFLOW", question.title(), question.prompt(),
                question.fieldsJson(), null, null, null, null, null, null);
    }
}
