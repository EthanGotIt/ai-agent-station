package cn.ethan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 路由模型配置：限制 Flash Thinking 的成本，保持结构化判定的稳定性。
 *
 * @author ethan
 * @date 2026-08-06
 */
@ConfigurationProperties(prefix = "ai-agent.router")
public record AgentRouterProperties(
        Boolean thinkingEnabled,
        Integer thinkingBudget,
        Integer historyTurns,
        Integer historyMaxCharacters
) {

    private static final int DEFAULT_THINKING_BUDGET = 512;
    private static final int MAX_THINKING_BUDGET = 2_048;
    private static final int DEFAULT_HISTORY_TURNS = 6;
    private static final int DEFAULT_HISTORY_MAX_CHARACTERS = 12_000;

    public AgentRouterProperties {
        thinkingEnabled = thinkingEnabled == null || thinkingEnabled;
        thinkingBudget = thinkingBudget == null
                ? DEFAULT_THINKING_BUDGET
                : thinkingBudget;
        historyTurns = historyTurns == null ? DEFAULT_HISTORY_TURNS : historyTurns;
        historyMaxCharacters = historyMaxCharacters == null
                ? DEFAULT_HISTORY_MAX_CHARACTERS
                : historyMaxCharacters;
        if (thinkingBudget < 0 || thinkingBudget > MAX_THINKING_BUDGET) {
            throw new IllegalArgumentException(
                    "router.thinkingBudget must be between 0 and 2048"
            );
        }
        if (historyTurns < 0 || historyTurns > 30) {
            throw new IllegalArgumentException("router.historyTurns must be between 0 and 30");
        }
        if (historyMaxCharacters < 1_000 || historyMaxCharacters > 100_000) {
            throw new IllegalArgumentException(
                    "router.historyMaxCharacters must be between 1000 and 100000"
            );
        }
    }
}
