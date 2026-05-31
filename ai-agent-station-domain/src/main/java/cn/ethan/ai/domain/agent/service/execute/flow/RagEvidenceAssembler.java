package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 RAG Advisor 元数据整理成 Runtime 可观测的 evidence payload。
 */
public class RagEvidenceAssembler {

    private static final List<String> PIPELINE = List.of(
            "query_rewrite",
            "hybrid_recall_pgvector_bm25",
            "rrf_fusion",
            "small_to_big_parent_expansion",
            "evidence_deduplicate"
    );

    public Map<String, Object> buildPayload(AgentPlanStepVO step, Integer stepIndex, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        List<RagEvidenceVO> evidences = simplifyEvidences(metadata.get("qa_retrieved_documents"));
        List<String> retrievalQueries = simplifyQueries(metadata.get("qa_retrieval_queries"));
        String skippedReason = readAsString(metadata.get("qa_retrieval_skipped_reason"));
        boolean ragStep = step != null && PlanStepTypeEnumVO.RAG.name().equalsIgnoreCase(step.getType());
        if (evidences.isEmpty() && retrievalQueries.isEmpty() && StringUtils.isBlank(skippedReason)) {
            return Map.of();
        }
        if (!ragStep && StringUtils.contains(skippedReason, "非 RAG 请求")) {
            return Map.of();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", stepIndex);
        payload.put("stepId", step == null ? "" : step.getStepId());
        payload.put("stepName", step == null ? "" : step.getName());
        payload.put("stepType", step == null ? "" : step.getType());
        payload.put("agenticRag", true);
        payload.put("pipeline", PIPELINE);
        payload.put("queries", retrievalQueries);
        payload.put("evidenceCount", evidences.size());
        payload.put("noEvidence", evidences.isEmpty() && !retrievalQueries.isEmpty());
        payload.put("skippedReason", skippedReason);
        payload.put("evidences", evidences);
        return payload;
    }

    public String buildMessage(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        String skippedReason = readAsString(payload.get("skippedReason"));
        if (StringUtils.isNotBlank(skippedReason)) {
            return "Agentic RAG 未执行检索：" + skippedReason;
        }
        Object evidenceCount = payload.get("evidenceCount");
        Object noEvidence = payload.get("noEvidence");
        if (Boolean.TRUE.equals(noEvidence)) {
            return "Agentic RAG 已执行检索，但未召回可用证据。";
        }
        return "Agentic RAG 已完成 Query Rewrite、混合召回、RRF 融合、父块扩展和证据去重，证据 "
                + (evidenceCount == null ? 0 : evidenceCount) + " 条。";
    }

    private List<RagEvidenceVO> simplifyEvidences(Object rawDocuments) {
        if (!(rawDocuments instanceof List<?> documents) || documents.isEmpty()) {
            return List.of();
        }

        Map<String, RagEvidenceVO> deduplicated = new LinkedHashMap<>();
        int index = 1;
        for (Object item : documents) {
            if (!(item instanceof Document document)) {
                continue;
            }
            Map<String, Object> metadata = document.getMetadata() == null ? Collections.emptyMap() : document.getMetadata();
            String parentChunkId = readMetadata(metadata, "qa_parent_chunk_id", "parent_chunk_id", "parentChunkId");
            String hitChunkId = readMetadata(metadata, "qa_hit_chunk_id", "chunk_id", "chunkId");
            RagEvidenceVO evidence = RagEvidenceVO.builder()
                    .evidenceId("evidence_" + index)
                    .documentId(readMetadata(metadata, "doc_id", "document_id", "documentId"))
                    .chunkId(readMetadata(metadata, "chunk_id", "chunkId"))
                    .hitChunkId(hitChunkId)
                    .parentChunkId(parentChunkId)
                    .parentKey(readMetadata(metadata, "qa_parent_key"))
                    .parentExpanded(StringUtils.isNotBlank(readMetadata(metadata, "qa_hit_chunk_id", "qa_parent_chunk_id")))
                    .sourceName(readMetadata(metadata, "source", "title", "file_name", "filename"))
                    .sectionTitle(readMetadata(metadata, "section_title", "sectionTitle", "qa_parent_chunk_id"))
                    .retrievalQuery(readMetadata(metadata, "qa_retrieval_query"))
                    .rank(resolveInteger(metadata.get("qa_retrieval_rank"), index))
                    .fusionRank(resolveInteger(metadata.get("qa_retrieval_rank"), index))
                    .sourceType(StringUtils.defaultIfBlank(readMetadata(metadata, "qa_retrieval_source"), "hybrid"))
                    .score(document.getScore())
                    .contentPreview(clip(document.getText(), 260))
                    .build();
            deduplicated.putIfAbsent(dedupeKey(evidence), evidence);
            index++;
        }
        return new ArrayList<>(deduplicated.values());
    }

    private String dedupeKey(RagEvidenceVO evidence) {
        if (StringUtils.isNotBlank(evidence.getParentKey())) {
            return evidence.getParentKey();
        }
        String documentId = StringUtils.defaultString(evidence.getDocumentId());
        String parentChunkId = StringUtils.defaultIfBlank(evidence.getParentChunkId(), evidence.getChunkId());
        if (StringUtils.isNotBlank(documentId) && StringUtils.isNotBlank(parentChunkId)) {
            return documentId + ":" + parentChunkId;
        }
        return StringUtils.defaultIfBlank(evidence.getContentPreview(), evidence.getEvidenceId());
    }

    private List<String> simplifyQueries(Object rawQueries) {
        if (!(rawQueries instanceof List<?> queryList) || queryList.isEmpty()) {
            return List.of();
        }

        List<String> queries = new ArrayList<>();
        for (Object item : queryList) {
            if (item == null) {
                continue;
            }
            String query = item.toString().trim();
            if (!query.isEmpty() && !queries.contains(query)) {
                queries.add(query);
            }
        }
        return queries;
    }

    private String readMetadata(Map<String, Object> metadata, String... keys) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString().trim();
            }
        }
        return "";
    }

    private String readAsString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private Integer resolveInteger(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private String clip(String text, int maxLength) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }
}
