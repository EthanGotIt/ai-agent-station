package cn.ethan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AgentScope ReAct 参数：集中约束模型执行与思考资源边界。
 *
 * @author ethan
 * @date 2026-08-06
 */
@ConfigurationProperties(prefix = "ai-agent.agentscope.react")
public record AgentScopeReActProperties(
        String apiKey,
        String baseUrl,
        Duration timeout,
        Integer maxIterations,
        Integer maxOutputTokens,
        Integer maxRetries,
        Boolean thinkingEnabled,
        Integer thinkingBudget
) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    public AgentScopeReActProperties {
        apiKey = apiKey == null ? "" : apiKey.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        maxIterations = maxIterations == null ? 8 : maxIterations;
        maxOutputTokens = maxOutputTokens == null ? 2_048 : maxOutputTokens;
        maxRetries = maxRetries == null ? 1 : maxRetries;
        thinkingEnabled = thinkingEnabled == null || thinkingEnabled;
        thinkingBudget = thinkingBudget == null ? 4_096 : thinkingBudget;

        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException(
                    "agentscope.react.timeout must be positive and no greater than PT10M"
            );
        }
        if (maxIterations < 1 || maxIterations > 32) {
            throw new IllegalArgumentException(
                    "agentscope.react.maxIterations must be between 1 and 32"
            );
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 65_536) {
            throw new IllegalArgumentException(
                    "agentscope.react.maxOutputTokens must be between 1 and 65536"
            );
        }
        if (maxRetries < 0 || maxRetries > 5) {
            throw new IllegalArgumentException(
                    "agentscope.react.maxRetries must be between 0 and 5"
            );
        }
        if (thinkingBudget < 0 || thinkingBudget > 65_536) {
            throw new IllegalArgumentException(
                    "agentscope.react.thinkingBudget must be between 0 and 65536"
            );
        }
    }
}
