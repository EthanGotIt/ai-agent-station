package cn.ethan.ai.domain.agent.service.execute.graph;

import cn.ethan.ai.domain.agent.adapter.port.IRagRetrievalPort;
import cn.ethan.ai.domain.agent.service.armory.factory.element.RagRetrievalSupport;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.vectorstore.SearchRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将现有 Hybrid RAG 链路暴露为 Graph Runtime 可自主调用的本地工具。
 */
public class GraphRagSearchToolCallback implements ToolCallback {

    private static final int MAX_REWRITE_QUERIES = 3;

    private static final int ROUTE_TOP_K = 8;

    private static final int EVIDENCE_TOP_K = 4;

    private static final ToolDefinition TOOL_DEFINITION = ToolDefinition.builder()
            .name("rag_search")
            .description("检索项目知识库。适用于需要依据已导入文档、项目资料或可解释证据回答的问题。")
            .inputSchema("""
                    {
                      "type": "object",
                      "properties": {
                        "query": {
                          "type": "string",
                          "description": "需要检索的完整问题或关键词"
                        }
                      },
                      "required": ["query"]
                    }
                    """)
            .build();

    private final IRagRetrievalPort ragRetrievalPort;

    private final RagRetrievalSupport retrievalSupport = new RagRetrievalSupport();

    private volatile Map<String, Object> lastEvidencePayload = Map.of();

    public GraphRagSearchToolCallback(IRagRetrievalPort ragRetrievalPort) {
        this.ragRetrievalPort = ragRetrievalPort;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return TOOL_DEFINITION;
    }

    @Override
    public String call(String toolInput) {
        RagSearchRequest request = JSON.parseObject(toolInput, RagSearchRequest.class);
        if (request == null || StringUtils.isBlank(request.query)) {
            throw new IllegalArgumentException("query 不能为空");
        }

        List<String> queries = retrievalSupport.rewriteQueries(request.query, MAX_REWRITE_QUERIES);
        List<List<Document>> routeDocuments = new ArrayList<>();
        for (String query : queries) {
            List<Document> documents = ragRetrievalPort.retrieve(
                    SearchRequest.builder().query(query).topK(ROUTE_TOP_K).build(),
                    Map.of("qa_retrieval_query", query)
            );
            routeDocuments.add(attachRetrievalQuery(documents, query));
        }

        List<Document> evidences = retrievalSupport.deduplicateByParent(
                retrievalSupport.rrfFuse(routeDocuments, EVIDENCE_TOP_K * 3, 60),
                EVIDENCE_TOP_K
        );
        Map<String, Object> payload = buildEvidencePayload(queries, evidences);
        lastEvidencePayload = payload;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("queries", queries);
        result.put("evidenceCount", evidences.size());
        result.put("evidenceContext", retrievalSupport.formatEvidenceContext(evidences));
        return JSON.toJSONString(result);
    }

    public Map<String, Object> lastEvidencePayload() {
        return lastEvidencePayload;
    }

    private List<Document> attachRetrievalQuery(List<Document> documents, String query) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return documents.stream()
                .map(document -> document.mutate()
                        .metadata("qa_retrieval_query", query)
                        .build())
                .toList();
    }

    private Map<String, Object> buildEvidencePayload(List<String> queries, List<Document> evidences) {
        List<Map<String, Object>> simplifiedEvidences = new ArrayList<>();
        for (int i = 0; i < evidences.size(); i++) {
            Document document = evidences.get(i);
            Map<String, Object> metadata = document.getMetadata();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("evidenceId", "evidence_" + (i + 1));
            evidence.put("documentId", metadata.getOrDefault("doc_id", ""));
            evidence.put("chunkId", metadata.getOrDefault("chunk_id", ""));
            evidence.put("parentChunkId", metadata.getOrDefault("qa_parent_chunk_id", metadata.getOrDefault("parent_chunk_id", "")));
            evidence.put("sourceName", metadata.getOrDefault("source", metadata.getOrDefault("title", "")));
            evidence.put("sectionTitle", metadata.getOrDefault("section_title", ""));
            evidence.put("retrievalQuery", metadata.getOrDefault("qa_retrieval_query", ""));
            evidence.put("score", document.getScore());
            evidence.put("contentPreview", clip(document.getText(), 260));
            simplifiedEvidences.add(evidence);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agenticRag", true);
        payload.put("pipeline", List.of(
                "query_rewrite",
                "hybrid_recall_pgvector_bm25",
                "rrf_fusion",
                "small_to_big_parent_expansion",
                "evidence_deduplicate"
        ));
        payload.put("queries", queries);
        payload.put("evidenceCount", evidences.size());
        payload.put("noEvidence", evidences.isEmpty());
        payload.put("evidences", simplifiedEvidences);
        return payload;
    }

    private String clip(String text, int maxLength) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    public static class RagSearchRequest {

        private String query;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

    }

}
