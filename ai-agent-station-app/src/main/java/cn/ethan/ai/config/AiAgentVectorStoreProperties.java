package cn.ethan.ai.config;

import lombok.Data;
import lombok.ToString;
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

    /**
     * 向量模型接口基地址。
     */
    private String baseUrl = "https://api.jina.ai/v1";

    /**
     * 向量模型密钥。
     */
    @ToString.Exclude
    private String apiKey;

    /**
     * 向量模型名称。
     */
    private String model = "jina-embeddings-v5-text-small";

    /**
     * 向量维度。
     */
    private Integer dimensions = 1024;

}
