package cn.ethan.dto;

/**
 * Agent 记忆编辑响应 DTO：返回持久化后的条目快照。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentMemoryEditResponseDto(AgentMemoryEntryDto entry) {

    public static AgentMemoryEditResponseDto from(AgentMemoryEntryDto entry) {
        return new AgentMemoryEditResponseDto(entry);
    }
}
