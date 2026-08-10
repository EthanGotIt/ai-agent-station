package cn.ethan.dto;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Agent 记忆编辑 DTO：受控键和值在业务边界限制为单条可审阅的短文本。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record AgentMemoryEditRequestDto(
        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 128, message = "sessionId 长度不能超过 128")
        String sessionId,

        @NotNull(message = "category 不能为空")
        AgentMemoryCategoryEnum category,

        @NotBlank(message = "memoryKey 不能为空")
        @Size(max = 64, message = "memoryKey 长度不能超过 64")
        String memoryKey,

        @NotBlank(message = "value 不能为空")
        @Size(max = 512, message = "value 长度不能超过 512")
        String value,

        @PositiveOrZero(message = "expectedVersion 不能小于 0")
        long expectedVersion,

        Instant expiresAt
) {
}
