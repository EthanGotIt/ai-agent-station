package cn.ethan.app.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * Thread 运行参数：约束上下文预算、工具结果上限和单 Turn 超时。
 *
 * @author ethan
 * @date 2026-08-19
 */
@ConfigurationProperties(prefix = "ai-agent.thread")
public record AgentThreadProperties(
        Integer contextMaxEstimatedTokens,
        Integer snapshotTriggerEstimatedTokens,
        Integer toolResultMaxCharacters,
        Integer outputReserveEstimatedTokens,
        Duration turnTimeout,
        Boolean compactionEnabled,
        Double compactionTriggerRatio,
        Double compactionRetainRatio,
        Integer summaryMaxOutputTokens,
        Integer toolPruneThresholdCharacters,
        Integer toolPruneHeadCharacters,
        Integer toolPruneTailCharacters,
        Integer maxOverflowRetries
) {

    public AgentThreadProperties(
            Integer contextMaxEstimatedTokens,
            Integer snapshotTriggerEstimatedTokens,
            Integer toolResultMaxCharacters,
            Integer outputReserveEstimatedTokens,
            Duration turnTimeout
    ) {
        this(contextMaxEstimatedTokens, snapshotTriggerEstimatedTokens, toolResultMaxCharacters,
                outputReserveEstimatedTokens, turnTimeout, null, null, null, null, null, null, null, null);
    }

    @ConstructorBinding
    public AgentThreadProperties {
        contextMaxEstimatedTokens = valueOrDefault(contextMaxEstimatedTokens, 65_536);
        snapshotTriggerEstimatedTokens = valueOrDefault(snapshotTriggerEstimatedTokens, 65_535);
        toolResultMaxCharacters = valueOrDefault(toolResultMaxCharacters, 8_000);
        outputReserveEstimatedTokens = valueOrDefault(outputReserveEstimatedTokens, 1_500);
        turnTimeout = turnTimeout == null ? Duration.ofMinutes(4) : turnTimeout;
        compactionEnabled = compactionEnabled == null || compactionEnabled;
        compactionTriggerRatio = compactionTriggerRatio == null ? 0.80 : compactionTriggerRatio;
        compactionRetainRatio = compactionRetainRatio == null ? 0.16 : compactionRetainRatio;
        summaryMaxOutputTokens = valueOrDefault(summaryMaxOutputTokens, 2_048);
        toolPruneThresholdCharacters = valueOrDefault(toolPruneThresholdCharacters, 8_000);
        toolPruneHeadCharacters = valueOrDefault(toolPruneHeadCharacters, 4_096);
        toolPruneTailCharacters = valueOrDefault(toolPruneTailCharacters, 1_024);
        maxOverflowRetries = valueOrDefault(maxOverflowRetries, 1);
        if (contextMaxEstimatedTokens < 1_000 || contextMaxEstimatedTokens > 100_000) {
            throw new IllegalArgumentException("thread.contextMaxEstimatedTokens must be between 1000 and 100000");
        }
        if (toolResultMaxCharacters < 256 || toolResultMaxCharacters > 100_000) {
            throw new IllegalArgumentException("thread.toolResultMaxCharacters must be between 256 and 100000");
        }
        if (outputReserveEstimatedTokens < 128
                || outputReserveEstimatedTokens >= contextMaxEstimatedTokens) {
            throw new IllegalArgumentException("thread.outputReserveEstimatedTokens must be below contextMaxEstimatedTokens");
        }
        if (turnTimeout.isZero() || turnTimeout.isNegative() || turnTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("thread.turnTimeout must be positive and no greater than PT10M");
        }
        if (compactionTriggerRatio <= 0 || compactionTriggerRatio >= 1
                || compactionRetainRatio <= 0 || compactionRetainRatio >= compactionTriggerRatio) {
            throw new IllegalArgumentException("thread compaction ratios are invalid");
        }
        if (summaryMaxOutputTokens < 128 || summaryMaxOutputTokens > 65_536) {
            throw new IllegalArgumentException("thread.summaryMaxOutputTokens must be between 128 and 65536");
        }
        if (toolPruneThresholdCharacters < 256
                || toolPruneHeadCharacters < 1
                || toolPruneTailCharacters < 1
                || toolPruneHeadCharacters + toolPruneTailCharacters >= toolPruneThresholdCharacters) {
            throw new IllegalArgumentException("thread tool result compaction limits are invalid");
        }
        if (maxOverflowRetries < 0 || maxOverflowRetries > 3) {
            throw new IllegalArgumentException("thread.maxOverflowRetries must be between 0 and 3");
        }
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    /** 2A-2 保留兼容读取；实际上下文不再依据旧摘要触发值。 */
    @Deprecated
    public Integer snapshotTriggerEstimatedTokens() {
        return snapshotTriggerEstimatedTokens;
    }
}
