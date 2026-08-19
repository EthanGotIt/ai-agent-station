package cn.ethan.dto;

import cn.ethan.core.agent.thread.model.AgentThreadModel;

import java.time.Instant;

/**
 * 类型职责：表达 Thread 的 HTTP 响应数据。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentThreadDto(
        String threadId,
        String title,
        String status,
        String contextType,
        String contextId,
        long nextSequence,
        Instant createdAt,
        Instant updatedAt
) {
    public static AgentThreadDto from(AgentThreadModel model) {
        return new AgentThreadDto(model.threadId(), model.title(), model.status().name(), model.contextType(),
                model.contextId(), model.nextSequence(), model.createdAt(), model.updatedAt());
    }
}
