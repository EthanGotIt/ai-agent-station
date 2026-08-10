package cn.ethan.dto;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 手工创建会话记忆 DTO。
 *
 * @author ethan
 * @date 2026-08-10
 */
public record AgentMemoryCreateRequestDto(
        @NotBlank @Size(max = 128) String sessionId,
        @NotNull AgentMemoryCategoryEnum category,
        @NotBlank @Size(max = 64) String memoryKey,
        @NotBlank @Size(max = 512) String value,
        Instant expiresAt
) {
}
