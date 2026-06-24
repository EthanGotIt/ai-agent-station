package cn.ethan.ai.test.evaluation;

import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalRequestVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import cn.ethan.ai.domain.agent.service.rag.RagIngestionService;
import cn.ethan.ai.rag.PgVectorEvidenceRetrievalPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live evaluation 的隔离语料管理器。固定 Advanced RAG 只在此保留为历史对照。
 */
final class RagEvaluationCorpusManager {

    static final String EVALUATION_RAG_ID = "7991";

    private static final String CORPUS = "/evaluation/rag-evaluation-project-corpus-v1.jsonl";
    private static final int TOP_K = 5;
    private static final int ROUTE_TOP_K = 12;
    private static final int RRF_RANK_CONSTANT = 60;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RagIngestionService ingestionService;
    private final PgVectorEvidenceRetrievalPort adaptiveRetrieval;
    private final VectorStore vectorStore;
    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate pgVectorJdbcTemplate;

    private List<CorpusEntry> corpusEntries = List.of();
    private Map<Long, Integer> originalRagConfigStatuses = Map.of();

    RagEvaluationCorpusManager(RagIngestionService ingestionService,
                               PgVectorEvidenceRetrievalPort adaptiveRetrieval,
                               VectorStore vectorStore,
                               JdbcTemplate mysqlJdbcTemplate,
                               JdbcTemplate pgVectorJdbcTemplate) {
        this.ingestionService = ingestionService;
        this.adaptiveRetrieval = adaptiveRetrieval;
        this.vectorStore = vectorStore;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.pgVectorJdbcTemplate = pgVectorJdbcTemplate;
    }

    void seed() throws Exception {
        cleanupEvaluationArtifacts();
        originalRagConfigStatuses = mysqlJdbcTemplate.query("""
                        SELECT id, status
                        FROM ai_client_config
                        WHERE source_type='client' AND source_id='2103' AND target_type='rag' AND target_id<>?
                        """, (resultSet, rowNum) -> Map.entry(resultSet.getLong("id"), resultSet.getInt("status")),
                EVALUATION_RAG_ID).stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
        originalRagConfigStatuses.keySet().forEach(id ->
                mysqlJdbcTemplate.update("UPDATE ai_client_config SET status=0 WHERE id=?", id));
        mysqlJdbcTemplate.update("""
                INSERT INTO ai_client_rag_order(rag_id, rag_name, knowledge_tag, status)
                VALUES (?, 'RAG Evaluation V1', 'evaluation-v1', 1)
                """, EVALUATION_RAG_ID);
        mysqlJdbcTemplate.update("""
                INSERT INTO ai_client_config(source_type, source_id, target_type, target_id, ext_param, status)
                VALUES ('client', '2103', 'rag', ?, '{}', 1)
                """, EVALUATION_RAG_ID);
        corpusEntries = loadCorpus();
        StringBuilder markdown = new StringBuilder("# RAG Evaluation V1 Project Corpus\n\n");
        for (CorpusEntry entry : corpusEntries) {
            markdown.append("## ").append(entry.evidenceId()).append("\n\n")
                    .append(entry.content()).append("\n\n");
        }
        ingestionService.ingestMarkdown(EVALUATION_RAG_ID, "RAG Evaluation V1",
                "evaluation:project-corpus-v1", markdown.toString());
    }

    void cleanup() {
        cleanupEvaluationArtifacts();
        originalRagConfigStatuses.forEach((id, status) ->
                mysqlJdbcTemplate.update("UPDATE ai_client_config SET status=? WHERE id=?", status, id));
        originalRagConfigStatuses = Map.of();
    }

    private void cleanupEvaluationArtifacts() {
        try {
            mysqlJdbcTemplate.update("DELETE FROM ai_client_config WHERE source_type='client' AND source_id='2103' AND target_type='rag' AND target_id=?", EVALUATION_RAG_ID);
            mysqlJdbcTemplate.update("DELETE FROM ai_rag_chunk WHERE rag_id=?", EVALUATION_RAG_ID);
            mysqlJdbcTemplate.update("DELETE FROM ai_rag_document WHERE rag_id=?", EVALUATION_RAG_ID);
            mysqlJdbcTemplate.update("DELETE FROM ai_client_rag_order WHERE rag_id=?", EVALUATION_RAG_ID);
        } catch (Exception ignored) {
            // A failed preflight may happen before the MySQL schema is available.
        }
        try {
            pgVectorJdbcTemplate.update("DELETE FROM vector_store_openai WHERE metadata::jsonb ->> 'rag_id' = ?", EVALUATION_RAG_ID);
        } catch (Exception ignored) {
            // Keep teardown best-effort so the original test failure remains visible.
        }
    }

    List<Document> retrieve(RagEvaluationSupport.RetrievalMode mode, String query) {
        return switch (mode) {
            case PGVECTOR_ONLY -> pgVectorOnly(query, TOP_K);
            case FIXED_ADVANCED_RAG_BASELINE -> fixedAdvanced(query);
            case ADAPTIVE_AGENTIC_RETRIEVAL -> adaptiveRetrieval.retrieve(EvidenceRetrievalRequestVO.builder()
                    .query(query)
                    .sourceType(EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE)
                    .ragIds(Set.of(EVALUATION_RAG_ID))
                    .topK(TOP_K)
                    .retrievalRound(1)
                    .build());
        };
    }

    private List<Document> pgVectorOnly(String query, int topK) {
        return withChannel(vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .filterExpression("rag_id == '" + EVALUATION_RAG_ID + "'")
                .build()), "pgvector", query);
    }

    private List<Document> fixedAdvanced(String query) {
        List<Document> vector = pgVectorOnly(query, ROUTE_TOP_K);
        List<Document> lexical = lexicalSearch(query, ROUTE_TOP_K);
        return rrfFuse(List.of(vector, lexical), TOP_K);
    }

    private List<Document> lexicalSearch(String query, int limit) {
        String normalizedQuery = normalize(query);
        List<String> terms = java.util.Arrays.stream(normalizedQuery.split("[^\\p{L}\\p{N}_.@-]+"))
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
        return corpusEntries.stream()
                .map(entry -> {
                    String haystack = normalize(entry.evidenceId() + " " + entry.title() + " " + entry.content());
                    long matches = terms.stream().filter(haystack::contains).count();
                    double score = terms.isEmpty() ? 0D : matches / (double) terms.size();
                    return Map.entry(entry, score);
                })
                .filter(entry -> entry.getValue() > 0D)
                .sorted(Map.Entry.<CorpusEntry, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().evidenceId()))
                .limit(limit)
                .map(entry -> Document.builder()
                        .id("lexical:" + entry.getKey().evidenceId())
                        .text(entry.getKey().content())
                        .metadata(Map.of(
                                "rag_id", EVALUATION_RAG_ID,
                                "title", entry.getKey().evidenceId(),
                                "source", "evaluation:project-corpus-v1",
                                "qa_retrieval_source", "test_lexical"
                        ))
                        .score(entry.getValue())
                        .build())
                .toList();
    }

    private List<Document> rrfFuse(List<List<Document>> routes, int limit) {
        Map<String, Document> documents = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<Document> route : routes) {
            for (int rank = 0; rank < route.size(); rank++) {
                Document document = route.get(rank);
                String key = documentKey(document);
                documents.putIfAbsent(key, document);
                scores.merge(key, 1D / (RRF_RANK_CONSTANT + rank + 1D), Double::sum);
            }
        }
        return documents.entrySet().stream()
                .map(entry -> entry.getValue().mutate().score(scores.get(entry.getKey())).build())
                .sorted(Comparator.comparing(document -> document.getScore() == null ? 0D : document.getScore(),
                        Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private String documentKey(Document document) {
        Object title = document.getMetadata().get("title");
        return title == null ? StringUtils.defaultString(document.getId()) : title.toString();
    }

    private List<Document> withChannel(List<Document> documents, String channel, String query) {
        if (documents == null) {
            return List.of();
        }
        List<Document> result = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put("qa_retrieval_source", channel);
            metadata.put("qa_retrieval_rank", index + 1);
            metadata.put("qa_retrieval_query", query);
            result.add(document.mutate().metadata(metadata).build());
        }
        return result;
    }

    private List<CorpusEntry> loadCorpus() throws Exception {
        List<CorpusEntry> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                java.util.Objects.requireNonNull(getClass().getResourceAsStream(CORPUS)),
                StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                result.add(new CorpusEntry(node.path("evidenceId").asText(),
                        node.path("title").asText(), node.path("content").asText()));
            }
        }
        return result;
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value).toLowerCase(Locale.ROOT);
    }

    private record CorpusEntry(String evidenceId, String title, String content) {
    }
}
