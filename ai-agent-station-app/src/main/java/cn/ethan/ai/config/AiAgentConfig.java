package cn.ethan.ai.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
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
@EnableConfigurationProperties({AiAgentVectorStoreProperties.class, AiAgentEvidenceRetrievalProperties.class})
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
            throw new IllegalStateException("启用 PgVector 向量库时必须配置向量模型密钥，请检查 OPENAI_API_KEY 或 ai-agent.vector-store.api-key");
        }

        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .model(embeddingModel)
                .dimensions(embeddingDimensions)
                .build();

        return PgVectorStore.builder(jdbcTemplate,
                        OpenAiEmbeddingModel.builder()
                                .options(embeddingOptions)
                                .metadataMode(MetadataMode.EMBED)
                                .build())
                .vectorTableName("vector_store_openai")
                .dimensions(embeddingDimensions)
                .build();
    }

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return TokenTextSplitter.builder().build();
    }

}
