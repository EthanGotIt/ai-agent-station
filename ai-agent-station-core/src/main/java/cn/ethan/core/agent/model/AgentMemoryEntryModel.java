package cn.ethan.core.agent.model;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;

import java.time.Instant;

/**
 * 会话记忆条目：受控键值和来源属性使记忆可审计、可失效且不会成为业务事实。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentMemoryEntryModel(
        String entryId,
        String sourceId,
        String userId,
        String sessionId,
        AgentMemoryCategoryEnum category,
        String memoryKey,
        String value,
        AgentMemoryOriginEnum origin,
        double confidence,
        long version,
        boolean deleted,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public AgentMemoryEntryModel {
        require(entryId, "entryId");
        require(userId, "userId");
        require(sessionId, "sessionId");
        if (category == null || origin == null) {
            throw new IllegalArgumentException("memory entry category and origin are required");
        }
        require(memoryKey, "memoryKey");
        require(value, "value");
        if (memoryKey.length() > 64 || value.length() > 512
                || confidence < 0.0 || confidence > 1.0 || version < 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("memory entry is invalid");
        }
        sourceId = sourceId == null || sourceId.isBlank() ? null : sourceId.strip();
        memoryKey = memoryKey.strip();
        value = value.strip();
    }

    public AgentMemoryEntryModel edit(
            AgentMemoryCategoryEnum category,
            String memoryKey,
            String value,
            Instant expiresAt,
            Instant now
    ) {
        return new AgentMemoryEntryModel(
                entryId, sourceId, userId, sessionId, category, memoryKey, value,
                AgentMemoryOriginEnum.MANUAL, 1.0, version + 1, false, expiresAt, createdAt, now
        );
    }

    public AgentMemoryEntryModel delete(Instant now) {
        return new AgentMemoryEntryModel(
                entryId, sourceId, userId, sessionId, category, memoryKey, value,
                origin, confidence, version + 1, true, expiresAt, createdAt, now
        );
    }

    public boolean expiredAt(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
