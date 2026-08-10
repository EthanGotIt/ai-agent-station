package cn.ethan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 记忆配置：生成和使用独立控制，默认均关闭。
 *
 * @author ethan
 * @date 2026-08-09
 */
@ConfigurationProperties(prefix = "ai-agent.memory")
public record AgentMemoryProperties(
        Boolean generationEnabled,
        Boolean usageEnabled,
        Boolean recordingEnabled,
        Duration idleDelay,
        Integer extractionQueueCapacity,
        Double minimumAutoConfidence
) {

    public AgentMemoryProperties {
        generationEnabled = generationEnabled != null
                ? generationEnabled : recordingEnabled != null && recordingEnabled;
        usageEnabled = usageEnabled != null && usageEnabled;
        recordingEnabled = generationEnabled;
        idleDelay = idleDelay == null ? Duration.ofSeconds(30) : idleDelay;
        extractionQueueCapacity = extractionQueueCapacity == null ? 64 : extractionQueueCapacity;
        minimumAutoConfidence = minimumAutoConfidence == null ? 0.75 : minimumAutoConfidence;
        if (idleDelay.isNegative() || idleDelay.isZero() || idleDelay.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("memory.idleDelay must be between PT0S and PT5M");
        }
        if (extractionQueueCapacity < 1 || extractionQueueCapacity > 1_024) {
            throw new IllegalArgumentException("memory.extractionQueueCapacity must be between 1 and 1024");
        }
        if (minimumAutoConfidence < 0.0 || minimumAutoConfidence > 1.0) {
            throw new IllegalArgumentException("memory.minimumAutoConfidence must be between 0 and 1");
        }
    }
}
