package cn.ethan.ai.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({AiAgentVectorStoreProperties.class, AiAgentHybridRetrievalProperties.class})
public class AiAgentConfig {

    /**
     * -- 删除旧的表（如果存在）
     * DROP TABLE IF EXISTS public.vector_store_openai;
     * <p>
     * -- 创建新的表，使用UUID作为主键
     * CREATE TABLE public.vector_store_openai (
     * id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
     * content TEXT NOT NULL,
     * metadata JSONB,
     * embedding VECTOR(1024)
     * );
     * <p>
     * SELECT * FROM vector_store_openai
     */
    @Bean("vectorStore")
    @Primary
    @ConditionalOnProperty(name = "ai-agent.vector-store.enabled", havingValue = "true")
    public PgVectorStore pgVectorStore(AiAgentVectorStoreProperties vectorStoreProperties,
                                       @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        String baseUrl = vectorStoreProperties.getBaseUrl();
        String apiKey = vectorStoreProperties.getApiKey();
        String embeddingModel = vectorStoreProperties.getModel();
        Integer embeddingDimensions = vectorStoreProperties.getDimensions();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("启用 PgVector 向量库时必须配置百炼模型密钥，请检查 OPENAI_API_KEY 或 ai-agent.vector-store.api-key");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(normalizeEmbeddingBaseUrl(baseUrl))
                .apiKey(apiKey)
                .build();

        return PgVectorStore.builder(jdbcTemplate, new OpenAiEmbeddingModel(
                        openAiApi,
                        MetadataMode.EMBED,
                        OpenAiEmbeddingOptions.builder()
                                .model(embeddingModel)
                                .dimensions(embeddingDimensions)
                                .build()))
                .vectorTableName("vector_store_openai")
                .dimensions(embeddingDimensions)
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter();
    }

    /**
     * Spring AI 的 OpenAiApi 会自行补齐 v1 路径。
     * DashScope 兼容模式配置若直接写到 /compatible-mode/v1，
     * 在 embeddings 场景下会出现 /v1/v1/embeddings 的 404。
     */
    private String normalizeEmbeddingBaseUrl(String rawBaseUrl) {
        if (!StringUtils.hasText(rawBaseUrl)) {
            return rawBaseUrl;
        }
        String normalized = rawBaseUrl.trim();
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        if (normalized.endsWith("/v1/")) {
            return normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

}
