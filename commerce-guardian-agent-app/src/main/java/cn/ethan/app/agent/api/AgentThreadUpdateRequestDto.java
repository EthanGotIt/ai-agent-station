package cn.ethan.app.agent.api;

import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收 Thread 标题和归档状态更新。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadUpdateRequestDto(
        @Size(max = 255) String title,
        boolean archive
) {
}
