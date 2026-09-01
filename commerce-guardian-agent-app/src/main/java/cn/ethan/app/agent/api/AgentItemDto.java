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
        int schemaVersion,
        String payload,
        Instant createdAt
) {
    public static AgentItemDto from(AgentItemModel item) {
        return new AgentItemDto(item.itemId(), item.turnId(), item.sequence(), item.kind().name(),
                item.schemaVersion(), item.payloadJson(), item.createdAt());
    }
}
