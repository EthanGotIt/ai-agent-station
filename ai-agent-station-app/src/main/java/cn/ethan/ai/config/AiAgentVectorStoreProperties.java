package cn.ethan.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Agent 向量库配置属性。
 */
@Data
@ConfigurationProperties(prefix = "ai-agent.vector-store")
public class AiAgentVectorStoreProperties {

    /**
     * 是否启用 PgVector 向量库。
     */
    private boolean enabled = false;

}
