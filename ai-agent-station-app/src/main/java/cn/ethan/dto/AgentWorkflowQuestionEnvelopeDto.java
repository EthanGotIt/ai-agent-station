package cn.ethan.dto;

import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;

/**
 * Workflow 问题 SSE 负载：将问题与恢复所需的运行版本作为原子负载返回。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentWorkflowQuestionEnvelopeDto(
        AgentWorkflowQuestionDto question,
        AgentChatWorkflowRunDto workflowRun
) {

    public static AgentWorkflowQuestionEnvelopeDto from(WorkflowQuestionModel question, WorkflowRunModel run) {
        return new AgentWorkflowQuestionEnvelopeDto(
                AgentWorkflowQuestionDto.from(question), AgentChatWorkflowRunDto.from(run)
        );
    }
}
