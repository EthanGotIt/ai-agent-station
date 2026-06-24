package cn.ethan.ai.config;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * AI Agent 自动装配健康指示器
 */
@Component("aiAgentArmory")
public class AiAgentArmoryHealthIndicator implements HealthIndicator {

    private final AiAgentArmoryReadyState readyState;

    public AiAgentArmoryHealthIndicator(AiAgentArmoryReadyState readyState) {
        this.readyState = readyState;
    }

    @Override
    public Health health() {
        Health.Builder builder = readyState.isReady() ? Health.up() : Health.down();
        builder.withDetail("stage", readyState.getStage())
                .withDetail("message", readyState.getMessage());
        if (readyState.getClientIds() != null && !readyState.getClientIds().isEmpty()) {
            builder.withDetail("clientIds", readyState.getClientIds());
        }
        return builder.build();
    }
}
