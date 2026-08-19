package cn.ethan.dto;

import cn.ethan.core.agent.thread.model.AgentItemModel;

import java.time.Instant;

/**
 * 类型职责：表达 v3 Thread Item 的有序事实。
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
