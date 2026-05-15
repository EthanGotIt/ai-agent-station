package cn.ethan.ai.rag;

import cn.ethan.ai.config.AiAgentHybridRetrievalProperties;
import cn.ethan.ai.domain.agent.adapter.port.IRagChildChunkIndexPort;
import cn.ethan.ai.domain.agent.model.valobj.RagIngestionDocumentVO;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 子块索引写入端口实现
 */
@Slf4j
@Service
public class RagChildChunkIndexPort implements IRagChildChunkIndexPort {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final AiAgentHybridRetrievalProperties properties;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final ObjectProvider<JdbcTemplate> pgVectorJdbcTemplateProvider;

    public RagChildChunkIndexPort(ObjectProvider<VectorStore> vectorStoreProvider,
                                  @Qualifier("pgVectorJdbcTemplate") ObjectProvider<JdbcTemplate> pgVectorJdbcTemplateProvider,
                                  AiAgentHybridRetrievalProperties properties) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.pgVectorJdbcTemplateProvider = pgVectorJdbcTemplateProvider;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(properties.getHttpTimeoutMillis(), 1000)))
                .build();
    }

    @Override
    public void replaceChildChunks(RagIngestionDocumentVO document, List<Document> childDocuments) {
        if (document == null) {
            throw new IllegalArgumentException("document 不能为空");
        }
        if (childDocuments == null || childDocuments.isEmpty()) {
            throw new IllegalArgumentException("childDocuments 不能为空");
        }

        replacePgVectorChildren(document, childDocuments);
        replaceElasticChildren(document, childDocuments);
        log.info("RAG 子块索引写入完成，ragId:{}，docId:{}，childChunks:{}",
                document.getRagId(), document.getDocId(), childDocuments.size());
    }

    private void replacePgVectorChildren(RagIngestionDocumentVO document, List<Document> childDocuments) {
        JdbcTemplate pgVectorJdbcTemplate = pgVectorJdbcTemplateProvider.getIfAvailable();
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (pgVectorJdbcTemplate == null || vectorStore == null) {
            throw new IllegalStateException("PgVector 未启用，无法执行 Parent-Child 导入");
        }

        pgVectorJdbcTemplate.update("""
                        DELETE FROM vector_store_openai
                         WHERE metadata ->> 'rag_id' = ?
                           AND metadata ->> 'doc_id' = ?
                        """,
                document.getRagId(), document.getDocId());

        vectorStore.accept(childDocuments);
    }

    private void replaceElasticChildren(RagIngestionDocumentVO document, List<Document> childDocuments) {
        try {
            deleteElasticDocument(document);
            bulkIndexElasticChildren(document, childDocuments);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ES 子块索引写入失败", e);
        } catch (IOException e) {
            throw new IllegalStateException("ES 子块索引写入失败", e);
        }
    }

    private void deleteElasticDocument(RagIngestionDocumentVO document) throws IOException, InterruptedException {
        String body = """
                {
                  "query": {
                    "bool": {
                      "must": [
                        {"term": {"rag_id": %s}},
                        {"term": {"doc_id": %s}}
                      ]
                    }
                  }
                }
                """.formatted(jsonQuote(document.getRagId()), jsonQuote(document.getDocId()));
        HttpResponse<String> response = sendRequest(HttpMethod.POST,
                "/" + properties.getEsIndexName() + "/_delete_by_query?refresh=true", body, MediaType.APPLICATION_JSON_VALUE);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("删除 ES 旧文档失败，status=" + response.statusCode() + ", body=" + response.body());
        }
    }

    private void bulkIndexElasticChildren(RagIngestionDocumentVO document, List<Document> childDocuments) throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        String updateTime = LocalDateTime.now().toString();
        for (Document childDocument : childDocuments) {
            Map<String, Object> metadata = new LinkedHashMap<>(childDocument.getMetadata());
            body.append(JSON.toJSONString(Map.of(
                    "index", Map.of(
                            "_index", properties.getEsIndexName(),
                            "_id", childDocument.getId()
                    )
            ))).append("\n");

            Map<String, Object> source = new LinkedHashMap<>();
            source.put("rag_id", document.getRagId());
            source.put("doc_id", document.getDocId());
            source.put("chunk_id", metadata.get("chunk_id"));
            source.put("parent_chunk_id", metadata.get("parent_chunk_id"));
            source.put("chunk_level", metadata.get("chunk_level"));
            source.put("chunk_order", metadata.get("chunk_order"));
            source.put("section_title", metadata.get("section_title"));
            source.put("title", metadata.get("title"));
            source.put("source", metadata.get("source"));
            source.put("chunk_text", childDocument.getText());
            source.put("metadata_json", JSON.toJSONString(metadata));
            source.put("status", metadata.getOrDefault("status", 1));
            source.put("update_time", updateTime);
            body.append(JSON.toJSONString(source)).append("\n");
        }

        HttpResponse<String> response = sendRequest(HttpMethod.POST, "/_bulk?refresh=true", body.toString(), "application/x-ndjson");
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("ES 批量写入失败，status=" + response.statusCode() + ", body=" + response.body());
        }
    }

    private HttpResponse<String> sendRequest(HttpMethod method, String path, String body, String contentType) throws IOException, InterruptedException {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getEsBaseUrl()) + normalizedPath))
                .timeout(Duration.ofMillis(Math.max(properties.getHttpTimeoutMillis(), 1000)));

        if (method == HttpMethod.POST) {
            builder.POST(HttpRequest.BodyPublishers.ofString(Objects.toString(body, ""), StandardCharsets.UTF_8));
            builder.header("Content-Type", contentType);
        } else if (method == HttpMethod.PUT) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(Objects.toString(body, ""), StandardCharsets.UTF_8));
            builder.header("Content-Type", contentType);
        } else {
            throw new IllegalArgumentException("不支持的 HTTP 方法：" + method);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String jsonQuote(String raw) {
        try {
            return objectMapper.writeValueAsString(Objects.toString(raw, ""));
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private String trimTrailingSlash(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

}
