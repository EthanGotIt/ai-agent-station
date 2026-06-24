package cn.ethan.ai.rag;

import cn.ethan.ai.config.AiAgentEvidenceRetrievalProperties;
import cn.ethan.ai.domain.agent.adapter.port.ILocalEvidenceRetrievalPort;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceRetrievalRequestVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目知识的唯一本地检索入口：PGVector 语义召回与 ragId 范围隔离。
 */
@Slf4j
@Service
public class PgVectorEvidenceRetrievalPort implements ILocalEvidenceRetrievalPort {

    private static final String META_RETRIEVAL_SOURCE = "qa_retrieval_source";
    private static final String META_RETRIEVAL_RANK = "qa_retrieval_rank";
    private static final String META_RETRIEVAL_QUERY = "qa_retrieval_query";

    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final AiAgentEvidenceRetrievalProperties properties;

    public PgVectorEvidenceRetrievalPort(ObjectProvider<VectorStore> vectorStoreProvider,
                                         AiAgentEvidenceRetrievalProperties properties) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.properties = properties;
    }

    @Override
    public List<Document> retrieve(EvidenceRetrievalRequestVO request) {
        if (request == null || StringUtils.isBlank(request.getQuery())
                || request.getRagIds() == null || request.getRagIds().isEmpty()) {
            return List.of();
        }
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            log.warn("PGVector 未注入，跳过项目知识检索。query:{}", request.getQuery());
            return List.of();
        }

        int topK = Math.max(Math.max(1, request.getTopK()), properties.getVectorRouteTopK());
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(request.getQuery())
                .topK(topK)
                .filterExpression(buildRagFilterExpression(request.getRagIds()))
                .build());
        List<Document> result = withEvidence(documents, request.getQuery(), request.getTopK());
        log.info("项目知识 evidence 检索完成，query：{}，ragIds：{}，vectorHits：{}，finalEvidence：{}",
                request.getQuery(), request.getRagIds(), documents == null ? 0 : documents.size(), result.size());
        return result;
    }

    static String buildRagFilterExpression(Set<String> ragIds) {
        List<String> allowed = ragIds.stream()
                .filter(StringUtils::isNotBlank)
                .map(id -> "rag_id == '" + id.replace("'", "''") + "'")
                .toList();
        if (allowed.isEmpty()) {
            return "rag_id == '__none__'";
        }
        return allowed.stream().collect(Collectors.joining(" || ", "(", ")"));
    }

    private List<Document> withEvidence(List<Document> documents, String query, int requestedTopK) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        int limit = requestedTopK <= 0 ? documents.size() : requestedTopK;
        List<Document> result = new ArrayList<>();
        for (int index = 0; index < documents.size() && result.size() < limit; index++) {
            Document document = documents.get(index);
            if (document == null || StringUtils.isBlank(document.getText())) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
            metadata.put(META_RETRIEVAL_SOURCE, "pgvector");
            metadata.put(META_RETRIEVAL_RANK, index + 1);
            metadata.put(META_RETRIEVAL_QUERY, query);
            result.add(document.mutate().metadata(metadata).build());
        }
        return result;
    }
}
