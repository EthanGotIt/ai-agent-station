package cn.ethan.core.agent.thread;


import java.time.Instant;

/**
 * Agent Turn：一次输入到一次稳定状态的执行回合。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentTurnModel(
        String turnId,
        String threadId,
        String userId,
        String clientRequestId,
        String input,
        AgentTurnStatusEnum status,
        int queuePosition,
        String workflowRunId,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        AgentWorkflowAnswerInput workflowAnswerInput,
        long version
) {
    public AgentTurnModel(
            String turnId,
            String threadId,
            String userId,
            String clientRequestId,
            String input,
            AgentTurnStatusEnum status,
            int queuePosition,
            String workflowRunId,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, null, 0L);
    }

    public AgentTurnModel(
            String turnId,
            String threadId,
            String userId,
            String clientRequestId,
            String input,
            AgentTurnStatusEnum status,
            int queuePosition,
            String workflowRunId,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            AgentWorkflowAnswerInput workflowAnswerInput
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, workflowAnswerInput, 0L);
    }

    public AgentTurnModel {
        if (turnId == null || turnId.isBlank() || threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("turnId and threadId must not be blank");
        }
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128) {
            throw new IllegalArgumentException("clientRequestId 长度必须为 1 到 128");
        }
        input = input == null ? "" : input.trim();
        status = status == null ? AgentTurnStatusEnum.QUEUED : status;
        if (version < 0) {
            throw new IllegalArgumentException("Turn version 不能为负数");
        }
        if (workflowAnswerInput != null && !workflowAnswerInput.runId().equals(workflowRunId)) {
            throw new IllegalArgumentException("回答 Turn 的 workflowRunId 与结构化输入不一致");
        }
    }

    public AgentTurnModel queued(int position) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.QUEUED, position, workflowRunId, errorCode,
                createdAt, startedAt, finishedAt, workflowAnswerInput, version + 1);
    }

    public AgentTurnModel active(Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.ACTIVE, queuePosition, workflowRunId, errorCode,
                createdAt, at, null, workflowAnswerInput, version + 1);
    }

    public AgentTurnModel terminal(AgentTurnStatusEnum terminal, String code, Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                terminal, queuePosition, workflowRunId, code, createdAt, startedAt, at,
                workflowAnswerInput, version + 1);
    }

    public AgentTurnModel workflow(String runId, AgentTurnStatusEnum nextStatus) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                nextStatus, queuePosition, runId, errorCode, createdAt, startedAt, finishedAt,
                workflowAnswerInput, version + 1);
    }
}
