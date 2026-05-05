package cn.ethan.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RAG 混合检索配置。
 */
@Data
@ConfigurationProperties(prefix = "ai-agent.hybrid-retrieval")
public class AiAgentHybridRetrievalProperties {

    /**
     * Elasticsearch 地址（ES 7.17.x）。
     */
    private String esBaseUrl = "http://127.0.0.1:9200";

    /**
     * 索引名称。
     */
    private String esIndexName = "ai_rag_chunk";

    /**
     * 索引模板名称。
     */
    private String esTemplateName = "ai_rag_chunk_template";

    /**
     * 启动时初始化 ES 索引模板与索引。
     */
    private boolean initializeEsOnStartup = true;

    /**
     * 向量路由召回数量。
     */
    private int vectorRouteTopK = 12;

    /**
     * BM25 路由召回数量。
     */
    private int bm25RouteTopK = 12;

    /**
     * RRF rank constant。
     */
    private int rrfRankConstant = 60;

    /**
     * 是否启用 Small-to-Big 父块回溯。
     */
    private boolean smallToBigEnabled = true;

    /**
     * HTTP 请求超时时间（毫秒）。
     */
    private int httpTimeoutMillis = 3000;

}
