package cn.ethan.core.agent.context;

/**
 * 类型职责：集中约束模型上下文压缩的比例、工具结果裁剪和摘要输出资源。
 *
 * @author ethan
 * @date 2026-09-05
 */
public record AgentContextCompactionSettings(
        boolean enabled,
        double triggerRatio,
        double retainRatio,
        int summaryMaxOutputTokens,
        int toolPruneThresholdCharacters,
        int toolPruneHeadCharacters,
        int toolPruneTailCharacters,
        int maxOverflowRetries
) {

    public AgentContextCompactionSettings {
        if (triggerRatio <= 0 || triggerRatio >= 1) {
            throw new IllegalArgumentException("context compaction triggerRatio must be between 0 and 1");
        }
        if (retainRatio <= 0 || retainRatio >= triggerRatio) {
            throw new IllegalArgumentException("context compaction retainRatio must be below triggerRatio");
        }
        if (summaryMaxOutputTokens < 128) {
            throw new IllegalArgumentException("context compaction summaryMaxOutputTokens must be at least 128");
        }
        if (toolPruneThresholdCharacters < 256
                || toolPruneHeadCharacters < 1
                || toolPruneTailCharacters < 1
                || toolPruneHeadCharacters + toolPruneTailCharacters >= toolPruneThresholdCharacters) {
            throw new IllegalArgumentException("invalid context compaction tool result limits");
        }
        if (maxOverflowRetries < 0 || maxOverflowRetries > 3) {
            throw new IllegalArgumentException("context compaction maxOverflowRetries must be between 0 and 3");
        }
    }

    public static AgentContextCompactionSettings defaults() {
        return new AgentContextCompactionSettings(true, 0.80, 0.16,
                2_048, 8_000, 4_096, 1_024, 1);
    }
}
