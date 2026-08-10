package cn.ethan.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Agent 对话请求 DTO：承载同步与流式 HTTP 接口接收的字段。
 *
 * @author ethan
 * @date 2026-08-05
 */
public record AgentChatRequestDto(
        @NotBlank(message = "requestId 不能为空")
        @Size(max = 128, message = "requestId 长度不能超过 128")
        String requestId,

        @NotBlank(message = "sessionId 不能为空")
        @Size(max = 128, message = "sessionId 长度不能超过 128")
        String sessionId,

        @NotBlank(message = "message 不能为空")
        @Size(max = 20_000, message = "message 长度不能超过 20000")
        String message,

        AgentMemoryOptionsDto memory
) {
}
