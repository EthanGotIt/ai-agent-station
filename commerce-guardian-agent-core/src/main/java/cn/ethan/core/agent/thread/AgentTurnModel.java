package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.thread.AgentTurnStatusEnum;

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
        Instant finishedAt
) {
    public AgentTurnModel {
        if (turnId == null || turnId.isBlank() || threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("turnId and threadId must not be blank");
        }
        if (clientRequestId == null || clientRequestId.isBlank()) {
            throw new IllegalArgumentException("clientRequestId must not be blank");
        }
        input = input == null ? "" : input.trim();
        status = status == null ? AgentTurnStatusEnum.QUEUED : status;
    }

    public AgentTurnModel queued(int position) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.QUEUED, position, workflowRunId, errorCode,
                createdAt, startedAt, finishedAt);
    }

    public AgentTurnModel active(Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.ACTIVE, queuePosition, workflowRunId, errorCode,
                createdAt, at, null);
    }

    public AgentTurnModel terminal(AgentTurnStatusEnum terminal, String code, Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                terminal, queuePosition, workflowRunId, code, createdAt, startedAt, at);
    }

    public AgentTurnModel workflow(String runId, AgentTurnStatusEnum nextStatus) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                nextStatus, queuePosition, runId, errorCode, createdAt, startedAt, finishedAt);
    }
}
