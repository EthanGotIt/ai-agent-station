package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.model.WorkflowRunModel;

/**
 * Agent 响应模型：统一承载各类路由执行器的返回结果。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentResponseModel(
        String requestId,
        RouteTypeEnum route,
        String executorId,
        String domainId,
        String workflowId,
        String operation,
        AgentStatusEnum status,
        String content,
        StructuredResultModel structuredResult,
        WorkflowQuestionModel question,
        WorkflowRunModel workflowRun,
        int inputTokens,
        int outputTokens
) {

    public static AgentResponseModel completed(AgentRequestModel request, RouteTypeEnum route,
                                               String executorId, String content) {
        return completed(request, route, executorId, content, 0, 0);
    }

    public static AgentResponseModel completed(AgentRequestModel request, RouteTypeEnum route,
                                               String executorId, String content,
                                               int inputTokens, int outputTokens) {
        return completed(request, route, executorId, "", "", "", content, null,
                inputTokens, outputTokens);
    }

    public static AgentResponseModel completed(
            AgentRequestModel request,
            RouteTypeEnum route,
            String executorId,
            String domainId,
            String workflowId,
            String operation,
            String content,
            StructuredResultModel structuredResult,
            int inputTokens,
            int outputTokens
    ) {
        return new AgentResponseModel(request.requestId(), route, executorId,
                domainId == null ? "" : domainId,
                workflowId == null ? "" : workflowId,
                operation == null ? "" : operation,
                AgentStatusEnum.COMPLETED, content, structuredResult, null, null,
                Math.max(inputTokens, 0), Math.max(outputTokens, 0));
    }

    public static AgentResponseModel waitingUserInput(
            AgentRequestModel request,
            String executorId,
            String domainId,
            String workflowId,
            String operation,
            String content,
            WorkflowQuestionModel question,
            WorkflowRunModel workflowRun
    ) {
        return new AgentResponseModel(
                request.requestId(),
                RouteTypeEnum.WORKFLOW,
                executorId,
                domainId,
                workflowId,
                operation,
                AgentStatusEnum.WAITING_USER_INPUT,
                content,
                null,
                question,
                workflowRun,
                0,
                0
        );
    }

    public static AgentResponseModel completedWorkflowRun(
            AgentRequestModel request,
            String executorId,
            String domainId,
            String workflowId,
            String operation,
            String content,
            StructuredResultModel structuredResult,
            WorkflowRunModel workflowRun
    ) {
        return new AgentResponseModel(
                request.requestId(),
                RouteTypeEnum.WORKFLOW,
                executorId,
                domainId,
                workflowId,
                operation,
                AgentStatusEnum.COMPLETED,
                content,
                structuredResult,
                null,
                workflowRun,
                0,
                0
        );
    }

    public static AgentResponseModel failed(AgentRequestModel request, RouteTypeEnum route,
                                            String executorId, String content) {
        return new AgentResponseModel(request.requestId(), route, executorId,
                "", "", "", AgentStatusEnum.FAILED, content, null, null, null, 0, 0);
    }

    public static AgentResponseModel cancelled(AgentRequestModel request) {
        return new AgentResponseModel(request.requestId(), RouteTypeEnum.CLARIFY, "",
                "", "", "", AgentStatusEnum.CANCELLED, "请求已取消", null, null, null, 0, 0);
    }
}
