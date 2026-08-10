package cn.ethan.dto;

import cn.ethan.core.agent.model.AgentMemoryEntryModel;

import java.time.Instant;

/**
 * Agent 记忆条目 DTO：管理接口只返回当前用户当前会话的条目。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentMemoryEntryDto(
        String entryId,
        String sourceId,
        String sessionId,
        String category,
        String memoryKey,
        String value,
        String origin,
        double confidence,
        long version,
        boolean deleted,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static AgentMemoryEntryDto from(AgentMemoryEntryModel entry) {
        return new AgentMemoryEntryDto(
                entry.entryId(), entry.sourceId(), entry.sessionId(), entry.category().name(), entry.memoryKey(),
                entry.value(), entry.origin().name(), entry.confidence(), entry.version(), entry.deleted(), entry.expiresAt(),
                entry.createdAt(), entry.updatedAt()
        );
    }
}
