package cn.ethan.core.workflow.model;

import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;

import java.time.Instant;
import java.util.Map;

/**
 * Workflow 运行模型：保存可恢复流程的身份、检查点和受控业务状态。
 *
 * @author ethan
 * @date 2026-08-07
 */
public record WorkflowRunModel(
        String runId,
        String userId,
        String sessionId,
        String domainId,
        String workflowId,
        String workflowVersion,
        String operation,
        WorkflowRunStatusEnum status,
        String checkpointId,
        long version,
        Map<String, String> state,
        WorkflowQuestionModel question,
        String resultContent,
        Instant createdAt,
        Instant updatedAt
) {

    public WorkflowRunModel {
        require(runId, "runId");
        require(userId, "userId");
        require(sessionId, "sessionId");
        require(domainId, "domainId");
        require(workflowId, "workflowId");
        require(workflowVersion, "workflowVersion");
        require(operation, "operation");
        if (status == null || version < 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("workflow run is incomplete");
        }
        checkpointId = checkpointId == null ? "" : checkpointId.strip();
        state = state == null ? Map.of() : Map.copyOf(state);
        if (status == WorkflowRunStatusEnum.WAITING_USER_INPUT && question == null) {
            throw new IllegalArgumentException("waiting workflow run question is required");
        }
        if (status != WorkflowRunStatusEnum.WAITING_USER_INPUT && question != null) {
            throw new IllegalArgumentException("terminal workflow run cannot retain question");
        }
        resultContent = resultContent == null ? "" : resultContent;
    }

    public WorkflowRunModel next(
            WorkflowRunStatusEnum nextStatus,
            String nextCheckpointId,
            Map<String, String> nextState,
            WorkflowQuestionModel nextQuestion,
            String nextResultContent,
            Instant now
    ) {
        return new WorkflowRunModel(
                runId,
                userId,
                sessionId,
                domainId,
                workflowId,
                workflowVersion,
                operation,
                nextStatus,
                nextCheckpointId,
                version + 1,
                nextState,
                nextQuestion,
                nextResultContent,
                createdAt,
                now
        );
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
