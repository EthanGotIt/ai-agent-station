package cn.ethan.app.bootstrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;

/**
 * 类型职责：约束 DeepSeek 模型、输出预算、重试次数和远程 HTTP 超时。
 *
 * @author ethan
 * @date 2026-08-21
 */
@ConfigurationProperties(prefix = "ai-agent.model")
public record AgentModelProperties(
        String name,
        Integer maxOutputTokens,
        Integer maxAttempts,
        Duration httpTimeout
) {

    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;
    private static final int DEFAULT_MAX_ATTEMPTS = 1;
    private static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration MAX_HTTP_TIMEOUT = Duration.ofMinutes(5);

    @ConstructorBinding
    public AgentModelProperties {
        name = name == null || name.isBlank() ? DEFAULT_MODEL : name.trim();
        maxOutputTokens = maxOutputTokens == null ? DEFAULT_MAX_OUTPUT_TOKENS : maxOutputTokens;
        maxAttempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        httpTimeout = httpTimeout == null ? DEFAULT_HTTP_TIMEOUT : httpTimeout;

        if (!DEFAULT_MODEL.equals(name)) {
            throw new IllegalArgumentException("DeepSeek 模型必须固定为 deepseek-chat，以禁用 thinking");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("model.maxOutputTokens must be between 1 and 8192");
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("model.maxAttempts must be between 1 and 3");
        }
        if (httpTimeout.isZero() || httpTimeout.isNegative()
                || httpTimeout.compareTo(MAX_HTTP_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "model.httpTimeout must be positive and no greater than PT5M"
            );
        }
    }
}
