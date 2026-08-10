package cn.ethan.dto;

import cn.ethan.core.agent.model.AgentResponseModel;

/**
 * Agent 对话响应 DTO：定义同步对话接口稳定的 JSON 返回结构。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentChatResponseDto(
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

    public static AgentChatResponseDto from(AgentResponseModel response) {
        return new AgentChatResponseDto(
                response.requestId(),
                response.route().name(),
                response.executorId(),
                response.domainId(),
                response.workflowId(),
                response.operation(),
                response.status().name(),
                response.content(),
                AgentStructuredResultDto.from(response.structuredResult()),
                AgentWorkflowQuestionDto.from(response.question()),
                AgentChatWorkflowRunDto.from(response.workflowRun())
        );
    }
}
