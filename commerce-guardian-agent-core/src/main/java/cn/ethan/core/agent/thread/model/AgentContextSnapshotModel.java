package cn.ethan.core.agent.thread.model;

import java.time.Instant;

/**
 * Thread 历史摘要快照。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentContextSnapshotModel(
        String snapshotId,
        String threadId,
        long throughSequence,
        long version,
        int estimatedTokens,
        String summary,
        Instant createdAt
) {
}
