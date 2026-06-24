package cn.ethan.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 自适应 evidence 检索配置。
 */
@Data
@ConfigurationProperties(prefix = "ai-agent.evidence-retrieval")
public class AiAgentEvidenceRetrievalProperties {

    private int vectorRouteTopK = 12;
}
