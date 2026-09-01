package cn.ethan.core.agent.context;

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
