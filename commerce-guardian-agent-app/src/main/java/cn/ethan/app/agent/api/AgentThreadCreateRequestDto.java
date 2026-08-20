package cn.ethan.app.agent.api;

import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收 Thread 创建所需的标题和可选业务上下文。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadCreateRequestDto(
        @Size(max = 255) String title,
        @Size(max = 64) String contextType,
        @Size(max = 128) String contextId
) {
}
