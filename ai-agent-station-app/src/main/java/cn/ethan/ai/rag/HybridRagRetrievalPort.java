package cn.ethan.ai.rag;

import cn.ethan.ai.config.AiAgentHybridRetrievalProperties;
import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.service.armory.factory.element.RagRetrievalSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RAG 混合检索端口实现：
 * 1. PGVector 语义召回
 * 2. Elasticsearch BM25 召回
 * 3. RRF 融合 + 父子回溯 + 父级去重
 */
@Slf4j
@Service
public class HybridRagRetrievalPort implements IRagRetrievalPort {

    private static final String META_RETRIEVAL_SOURCE = "qa_retrieval_source";
    private static final String META_RETRIEVAL_RANK = "qa_retrieval_rank";
    private static final String META_RETRIEVAL_QUERY = "qa_retrieval_query";
    private static final int MAX_BM25_QUERY_CHARS = 240;
    private static final int MAX_BM25_QUERY_TERMS = 48;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagRetrievalSupport ragRetrievalSupport = new RagRetrievalSupport();
    private final HttpClient httpClient;
    private final AiAgentHybridRetrievalProperties properties;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final JdbcTemplate mysqlJdbcTemplate;

    public HybridRagRetrievalPort(ObjectProvider<VectorStore> vectorStoreProvider,
                                  @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                                  AiAgentHybridRetrievalProperties properties) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(properties.getHttpTimeoutMillis(), 1000)))
                .build();
        initializeElasticsearchIfNecessary();
    }

    @Override
    public List<Document> retrieve(SearchRequest searchRequest, Map<String, Object> context) {
        if (searchRequest == null || StringUtils.isBlank(searchRequest.getQuery())) {
            return List.of();
        }

        int finalTopK = resolveFinalTopK(searchRequest);
        List<Document> vectorDocuments = vectorSearch(searchRequest);
        List<Document> bm25Documents = bm25Search(searchRequest.getQuery(), finalTopK);

        List<Document> fused = ragRetrievalSupport.rrfFuse(List.of(vectorDocuments, bm25Documents), finalTopK * 3, properties.getRrfRankConstant());
        List<Document> expanded = properties.isSmallToBigEnabled()
                ? ragRetrievalSupport.expandWithParent(fused, this::resolveParentChunkDocument)
                : fused;
        List<Document> finalDocuments = ragRetrievalSupport.deduplicateByParent(expanded, finalTopK);
        log.info("RAG 混合检索完成，query：{}，queryCount：1，vectorHits：{}，bm25Hits：{}，finalEvidence：{}",
                searchRequest.getQuery(), vectorDocuments.size(), bm25Documents.size(), finalDocuments.size());
        return finalDocuments;
    }

    private List<Document> vectorSearch(SearchRequest originalRequest) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("PGVector 未注入，跳过语义召回。query:{}", originalRequest.getQuery());
            return List.of();
        }
        SearchRequest vectorRequest = SearchRequest.from(originalRequest)
                .topK(Math.max(properties.getVectorRouteTopK(), originalRequest.getTopK()))
                .build();
        List<Document> raw = vectorStore.similaritySearch(vectorRequest);
        return withEvidence(raw, "pgvector", originalRequest.getQuery());
    }

    private List<Document> bm25Search(String query, int finalTopK) {
        String normalizedQuery = normalizeBm25Query(query);
        if (StringUtils.isBlank(normalizedQuery)) {
            log.warn("BM25 检索已跳过，原因：查询为空或被裁剪后无有效内容。");
            return List.of();
        }
        int routeTopK = Math.max(finalTopK, properties.getBm25RouteTopK());
        String body = """
                {
                  "size": %d,
                  "_source": [
                    "rag_id",
                    "doc_id",
                    "chunk_id",
                    "parent_chunk_id",
                    "chunk_level",
                    "chunk_order",
                    "section_title",
                    "chunk_text",
                    "title",
                    "source",
                    "metadata_json"
                  ],
                  "query": {
                    "bool": {
                      "filter": [
                        {
                          "term": {
                            "chunk_level": 2
                          }
                        }
                      ],
                      "should": [
                        {
                          "multi_match": {
                            "query": %s,
                            "fields": ["chunk_text^3", "title^2", "chunk_text.ngram"],
                            "type": "most_fields"
                          }
                        },
                        {
                          "match_phrase": {
                            "chunk_text": {
                              "query": %s,
                              "boost": 2
                            }
                          }
                        }
                      ],
                      "minimum_should_match": 1
                    }
                  }
                }
                """.formatted(routeTopK, jsonQuote(normalizedQuery), jsonQuote(normalizedQuery));

        try {
            HttpResponse<String> response = sendRequest(HttpMethod.POST, "/" + properties.getEsIndexName() + "/_search", body);
            if (response.statusCode() / 100 != 2) {
                log.warn("BM25 检索失败，status:{}, body:{}", response.statusCode(), response.body());
                return List.of();
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return List.of();
            }

            List<Document> documents = new ArrayList<>();
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                String text = trimToEmpty(source.path("chunk_text").asText(""));
                if (StringUtils.isBlank(text)) {
                    continue;
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("rag_id", trimToEmpty(source.path("rag_id").asText("")));
                metadata.put("doc_id", trimToEmpty(source.path("doc_id").asText("")));
                metadata.put("chunk_id", trimToEmpty(source.path("chunk_id").asText("")));
                metadata.put("parent_chunk_id", trimToEmpty(source.path("parent_chunk_id").asText("")));
                metadata.put("chunk_level", source.path("chunk_level").asInt(2));
                metadata.put("chunk_order", source.path("chunk_order").asInt(0));
                metadata.put("section_title", trimToEmpty(source.path("section_title").asText("")));
                metadata.put("title", trimToEmpty(source.path("title").asText("")));
                metadata.put("source", trimToEmpty(source.path("source").asText("")));

                mergeMetadataJson(metadata, source.path("metadata_json").asText(""));
                documents.add(Document.builder()
                        .id(trimToEmpty(hit.path("_id").asText("")))
                        .text(text)
                        .metadata(metadata)
                        .score(hit.path("_score").asDouble(0D))
                        .build());
            }
            return withEvidence(documents, "bm25", normalizedQuery);
        } catch (Exception e) {
            log.warn("BM25 检索异常，query:{}", normalizedQuery, e);
            return List.of();
        }
    }

    private String normalizeBm25Query(String query) {
        if (StringUtils.isBlank(query)) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        String[] terms = normalized.split(" ");
        if (terms.length > MAX_BM25_QUERY_TERMS) {
            normalized = String.join(" ", Arrays.copyOf(terms, MAX_BM25_QUERY_TERMS));
        }
        if (normalized.length() > MAX_BM25_QUERY_CHARS) {
            normalized = normalized.substring(0, MAX_BM25_QUERY_CHARS).trim();
        }
        return normalized;
    }

    private List<Document> withEvidence(List<Document> documents, String source, String query) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Document document = documents.get(i);
            if (document == null || StringUtils.isBlank(document.getText())) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (document.getMetadata() != null) {
                metadata.putAll(document.getMetadata());
            }
            metadata.put(META_RETRIEVAL_SOURCE, source);
            metadata.put(META_RETRIEVAL_RANK, i + 1);
            metadata.put(META_RETRIEVAL_QUERY, query);
            result.add(document.mutate().metadata(metadata).build());
        }
        return result;
    }

    /**
     * 子块命中后回查父块内容。
     */
    private Document resolveParentChunkDocument(Document hitDocument) {
        if (hitDocument == null || hitDocument.getMetadata() == null) {
            return null;
        }
        String parentChunkId = metadata(hitDocument, "parent_chunk_id");
        String docId = metadata(hitDocument, "doc_id");
        if (StringUtils.isBlank(parentChunkId) || StringUtils.isBlank(docId)) {
            return null;
        }

        try {
            return mysqlJdbcTemplate.query(
                    """
                            SELECT c.rag_id,
                                   c.doc_id,
                                   c.chunk_id,
                                   c.parent_chunk_id,
                                   c.chunk_text,
                                   c.metadata_json,
                                   d.title,
                                   d.source
                            FROM ai_rag_chunk c
                            LEFT JOIN ai_rag_document d
                                   ON c.rag_id = d.rag_id
                                  AND c.doc_id = d.doc_id
                            WHERE c.doc_id = ?
                              AND c.chunk_id = ?
                              AND c.chunk_level = 1
                              AND c.status = 1
                            LIMIT 1
                            """,
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        String text = trimToEmpty(rs.getString("chunk_text"));
                        if (StringUtils.isBlank(text)) {
                            return null;
                        }
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("rag_id", trimToEmpty(rs.getString("rag_id")));
                        metadata.put("doc_id", trimToEmpty(rs.getString("doc_id")));
                        metadata.put("chunk_id", trimToEmpty(rs.getString("chunk_id")));
                        metadata.put("parent_chunk_id", trimToEmpty(rs.getString("parent_chunk_id")));
                        metadata.put("title", trimToEmpty(rs.getString("title")));
                        metadata.put("source", trimToEmpty(rs.getString("source")));
                        mergeMetadataJson(metadata, trimToEmpty(rs.getString("metadata_json")));
                        return Document.builder()
                                .id(metadata.get("doc_id") + ":" + metadata.get("chunk_id"))
                                .text(text)
                                .metadata(metadata)
                                .score(hitDocument.getScore())
                                .build();
                    },
                    docId, parentChunkId
            );
        } catch (Exception e) {
            log.warn("父块回溯失败，docId:{}，parentChunkId:{}", docId, parentChunkId, e);
            return null;
        }
    }

    private void initializeElasticsearchIfNecessary() {
        if (!properties.isInitializeEsOnStartup()) {
            return;
        }
        try {
            upsertIndexTemplate();
            createIndexIfAbsent();
            log.info("RAG ES 索引初始化完成，index:{}。", properties.getEsIndexName());
        } catch (Exception e) {
            log.warn("RAG ES 索引初始化失败，可继续运行（检索时会重试 HTTP）。", e);
        }
    }

    private void upsertIndexTemplate() throws IOException, InterruptedException {
        String templateBody = """
                {
                  "index_patterns": ["%s*"],
                  "settings": {
                    "number_of_shards": 1,
                    "number_of_replicas": 0,
                    "analysis": {
                      "tokenizer": {
                        "rag_ngram_tokenizer": {
                          "type": "ngram",
                          "min_gram": 2,
                          "max_gram": 3,
                          "token_chars": ["letter", "digit"]
                        }
                      },
                      "analyzer": {
                        "rag_ngram_analyzer": {
                          "type": "custom",
                          "tokenizer": "rag_ngram_tokenizer",
                          "filter": ["lowercase"]
                        }
                      }
                    }
                  },
                  "mappings": {
                    "properties": {
                      "rag_id": {"type": "keyword"},
                      "doc_id": {"type": "keyword"},
                      "chunk_id": {"type": "keyword"},
                      "parent_chunk_id": {"type": "keyword"},
                      "chunk_level": {"type": "integer"},
                      "chunk_order": {"type": "integer"},
                      "section_title": {
                        "type": "text",
                        "fields": {
                          "keyword": {"type": "keyword", "ignore_above": 256}
                        }
                      },
                      "title": {
                        "type": "text",
                        "fields": {
                          "keyword": {"type": "keyword", "ignore_above": 256}
                        }
                      },
                      "source": {
                        "type": "text",
                        "fields": {
                          "keyword": {"type": "keyword", "ignore_above": 256}
                        }
                      },
                      "chunk_text": {
                        "type": "text",
                        "fields": {
                          "ngram": {
                            "type": "text",
                            "analyzer": "rag_ngram_analyzer",
                            "search_analyzer": "standard"
                          }
                        }
                      },
                      "metadata_json": {"type": "text"},
                      "status": {"type": "integer"},
                      "update_time": {"type": "date"}
                    }
                  }
                }
                """.formatted(properties.getEsIndexName());
        HttpResponse<String> response = sendRequest(HttpMethod.PUT, "/_template/" + properties.getEsTemplateName(), templateBody);
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("创建 ES 模板失败，status=" + response.statusCode() + ", body=" + response.body());
        }
    }

    private void createIndexIfAbsent() throws IOException, InterruptedException {
        HttpResponse<String> head = sendRequest(HttpMethod.HEAD, "/" + properties.getEsIndexName(), null);
        if (head.statusCode() == 200) {
            return;
        }
        if (head.statusCode() != 404) {
            throw new IllegalStateException("检查 ES 索引失败，status=" + head.statusCode() + ", body=" + head.body());
        }
        String body = """
                {
                  "aliases": {
                    "%s_alias": {}
                  }
                }
                """.formatted(properties.getEsIndexName());
        HttpResponse<String> create = sendRequest(HttpMethod.PUT, "/" + properties.getEsIndexName(), body);
        if (create.statusCode() / 100 != 2) {
            throw new IllegalStateException("创建 ES 索引失败，status=" + create.statusCode() + ", body=" + create.body());
        }
    }

    private HttpResponse<String> sendRequest(HttpMethod method, String path, String body) throws IOException, InterruptedException {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(properties.getEsBaseUrl()) + normalizedPath))
                .timeout(Duration.ofMillis(Math.max(properties.getHttpTimeoutMillis(), 1000)));

        if (method == HttpMethod.GET) {
            builder.GET();
        } else if (method == HttpMethod.POST) {
            builder.POST(HttpRequest.BodyPublishers.ofString(Objects.toString(body, ""), StandardCharsets.UTF_8));
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        } else if (method == HttpMethod.PUT) {
            builder.PUT(HttpRequest.BodyPublishers.ofString(Objects.toString(body, ""), StandardCharsets.UTF_8));
            builder.header("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        } else if (method == HttpMethod.HEAD) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            throw new IllegalArgumentException("不支持的 HTTP 方法：" + method);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private int resolveFinalTopK(SearchRequest searchRequest) {
        return searchRequest.getTopK() <= 0 ? 4 : searchRequest.getTopK();
    }

    private String metadata(Document document, String key) {
        if (document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(key);
        return value == null ? "" : trimToEmpty(value.toString());
    }

    private void mergeMetadataJson(Map<String, Object> metadata, String metadataJson) {
        if (StringUtils.isBlank(metadataJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            if (!node.isObject()) {
                return;
            }
            node.fields().forEachRemaining(entry -> {
                if (!metadata.containsKey(entry.getKey()) && !entry.getValue().isContainerNode()) {
                    metadata.put(entry.getKey(), entry.getValue().asText(""));
                }
            });
        } catch (Exception e) {
            log.debug("解析 metadata_json 失败，已忽略。metadataJson:{}", metadataJson, e);
        }
    }

    private String jsonQuote(String raw) {
        try {
            return objectMapper.writeValueAsString(Objects.toString(raw, ""));
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

}
