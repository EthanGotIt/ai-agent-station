package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentItemModel;

import java.time.Instant;

/**
 * 类型职责：表达 Thread Item 的有序事实。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentItemDto(
        String itemId,
        String turnId,
        long sequence,
        String type,
        String payload,
        Instant createdAt
) {
    public static AgentItemDto from(AgentItemModel item) {
        return new AgentItemDto(item.itemId(), item.turnId(), item.sequence(), item.type().name(), item.payload(), item.createdAt());
    }
}
