package cn.ethan.core.agent.thread.model;

import cn.ethan.core.agent.thread.enums.AgentItemTypeEnum;

import java.time.Instant;

/**
 * Thread 内的有序、可恢复事实。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentItemModel(
        String itemId,
        String threadId,
        String turnId,
        long sequence,
        AgentItemTypeEnum type,
        String payload,
        Instant createdAt
) {
    public AgentItemModel {
        if (itemId == null || itemId.isBlank() || threadId == null || threadId.isBlank()) {
            throw new IllegalArgumentException("itemId and threadId must not be blank");
        }
        if (sequence < 0 || type == null) {
            throw new IllegalArgumentException("sequence and type must be valid");
        }
        payload = payload == null ? "" : payload;
    }
}
