package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.AgentMemorySourceEnum;

import java.time.Instant;

/**
 * 记忆来源模型：保存一条记忆由哪个会话回合产生。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentMemorySourceModel(
        String sourceId,
        String userId,
        String sessionId,
        String requestId,
        AgentMemorySourceEnum sourceType,
        Instant createdAt
) {

    public AgentMemorySourceModel {
        require(sourceId, "sourceId");
        require(userId, "userId");
        require(sessionId, "sessionId");
        require(requestId, "requestId");
        if (sourceType == null || createdAt == null) {
            throw new IllegalArgumentException("memory source is incomplete");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
