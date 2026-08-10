package cn.ethan.dto;

import cn.ethan.core.agent.model.AgentResponseModel;

/**
 * Workflow 回答响应 DTO：保持与对话响应一致的结果结构。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentWorkflowAnswerResponseDto(
        String requestId,
        String route,
        String executorId,
        String domainId,
        String workflowId,
        String operation,
        String status,
        String content,
        AgentStructuredResultDto result,
        AgentWorkflowQuestionDto question,
        AgentChatWorkflowRunDto workflowRun
) {

    public static AgentWorkflowAnswerResponseDto from(AgentResponseModel response) {
        return new AgentWorkflowAnswerResponseDto(
                response.requestId(), response.route().name(), response.executorId(), response.domainId(),
                response.workflowId(), response.operation(), response.status().name(), response.content(),
                AgentStructuredResultDto.from(response.structuredResult()),
                AgentWorkflowQuestionDto.from(response.question()),
                AgentChatWorkflowRunDto.from(response.workflowRun())
        );
    }
}
