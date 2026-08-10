package cn.ethan.dto;

/**
 * 手工创建会话记忆响应 DTO：返回已保存的人工维护条目。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryCreateResponseDto(AgentMemoryEntryDto entry) {

    public static AgentMemoryCreateResponseDto from(AgentMemoryEntryDto entry) {
        return new AgentMemoryCreateResponseDto(entry);
    }
}
