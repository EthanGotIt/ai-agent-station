package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentTurnModel;

import java.time.Instant;

/**
 * 类型职责：表达 Turn 入队后的稳定响应。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentTurnAcceptedResponseDto(
        String turnId,
        String threadId,
        String status,
        int queuePosition,
        String workflowRunId,
        Instant createdAt
) {
    public static AgentTurnAcceptedResponseDto from(AgentTurnModel turn) {
        return new AgentTurnAcceptedResponseDto(turn.turnId(), turn.threadId(), turn.status().name(),
                turn.queuePosition(), turn.workflowRunId(), turn.createdAt());
    }
}
