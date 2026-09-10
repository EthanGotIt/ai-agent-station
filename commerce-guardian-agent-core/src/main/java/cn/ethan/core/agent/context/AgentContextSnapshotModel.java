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
        Instant createdAt,
        int formatVersion,
        String baseSnapshotId,
        long sourceFromSequence,
        int sourceEstimatedTokens,
        String promptVersion,
        int summaryMaxOutputTokens
) {

    /** 兼容 2A-1 及历史测试构造；旧快照不会进入新的模型上下文。 */
    public AgentContextSnapshotModel(
            String snapshotId,
            String threadId,
            long throughSequence,
            long version,
            int estimatedTokens,
            String summary,
            Instant createdAt
    ) {
        this(snapshotId, threadId, throughSequence, version, estimatedTokens, summary, createdAt,
                1, null, 1L, estimatedTokens, "legacy", 0);
    }

    public AgentContextSnapshotModel {
        snapshotId = snapshotId == null ? "" : snapshotId;
        threadId = threadId == null ? "" : threadId;
        summary = summary == null ? "" : summary;
        baseSnapshotId = baseSnapshotId == null || baseSnapshotId.isBlank() ? null : baseSnapshotId;
        promptVersion = promptVersion == null || promptVersion.isBlank() ? "legacy" : promptVersion;
    }

    public boolean validV2() {
        return formatVersion == 2
                && !snapshotId.isBlank()
                && !threadId.isBlank()
                && throughSequence >= 0
                && version > 0
                && estimatedTokens > 0
                && !summary.isBlank()
                && createdAt != null
                && sourceFromSequence >= 1
                && throughSequence >= sourceFromSequence - 1
                && sourceEstimatedTokens > 0
                && summaryMaxOutputTokens >= 128
                && "context-summary-v2".equalsIgnoreCase(promptVersion)
                && !snapshotId.equals(baseSnapshotId)
                && AgentContextTokenEstimator.estimateText(summary) == estimatedTokens;
    }
}
