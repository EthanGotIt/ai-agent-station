package cn.ethan.app.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
        Duration turnTimeout
) {

    public AgentThreadProperties {
        contextMaxEstimatedTokens = valueOrDefault(contextMaxEstimatedTokens, 12_000);
        snapshotTriggerEstimatedTokens = valueOrDefault(snapshotTriggerEstimatedTokens, 9_000);
        toolResultMaxCharacters = valueOrDefault(toolResultMaxCharacters, 8_000);
        outputReserveEstimatedTokens = valueOrDefault(outputReserveEstimatedTokens, 1_500);
        turnTimeout = turnTimeout == null ? Duration.ofMinutes(4) : turnTimeout;
        if (contextMaxEstimatedTokens < 1_000 || contextMaxEstimatedTokens > 100_000) {
            throw new IllegalArgumentException("thread.contextMaxEstimatedTokens must be between 1000 and 100000");
        }
        if (snapshotTriggerEstimatedTokens < 500
                || snapshotTriggerEstimatedTokens >= contextMaxEstimatedTokens) {
            throw new IllegalArgumentException("thread.snapshotTriggerEstimatedTokens must be below contextMaxEstimatedTokens");
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
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }
}
