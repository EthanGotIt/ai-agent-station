package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.valobj.AgenticRagTraceVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceBoardVO;
import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 将 Evidence Board 转为对外可复盘 trace，不泄露完整工具原始输出。
 */
@Service
public class EvidenceTraceAssembler {

    public AgenticRagTraceVO assemble(String question, EvidenceBoardVO board, EvidencePolicy.Decision policy) {
        List<RagEvidenceVO> evidences = new ArrayList<>();
        int rank = 1;
        for (Document document : board.immutableEvidence()) {
            evidences.add(RagEvidenceVO.builder()
                    .evidenceId("E" + rank)
                    .documentId(metadata(document, "doc_id"))
                    .chunkId(metadata(document, "chunk_id"))
                    .sourceName(StringUtils.defaultIfBlank(metadata(document, "title", "source"), "未知来源"))
                    .sourceType(metadata(document, "qa_evidence_source_type"))
                    .sectionTitle(metadata(document, "section_title"))
                    .retrievalQuery(metadata(document, "qa_retrieval_query"))
                    .uri(metadata(document, "uri", "url"))
                    .toolName(metadata(document, "tool_name"))
                    .retrievedAt(metadata(document, "retrieved_at"))
                    .attributable(Boolean.parseBoolean(metadata(document, "qa_evidence_attributable")))
                    .rank(rank)
                    .fusionRank(rank)
                    .score(document.getScore())
                    .contentPreview(limit(document.getText(), 240))
                    .build());
            rank++;
        }
        return AgenticRagTraceVO.builder()
                .originalQuestion(question)
                .intent("evidence_governed")
                .retrievalRounds(new ArrayList<>(board.getRetrievalRounds()))
                .finalEvidences(evidences)
                .evidenceSufficient(policy.allowed() && (!policy.evidenceRequired() || !evidences.isEmpty()))
                .noEvidenceReason(policy.allowed() ? "" : policy.reason())
                .policyResult(policy.groundingMode() + ": " + policy.reason())
                .build();
    }

    private String metadata(Document document, String... keys) {
        if (document == null || document.getMetadata() == null) {
            return "";
        }
        for (String key : keys) {
            Object value = document.getMetadata().get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString();
            }
        }
        return "";
    }

    private String limit(String content, int maxLength) {
        String actual = StringUtils.defaultString(content).trim().replaceAll("\\s+", " ");
        return actual.length() <= maxLength ? actual : actual.substring(0, maxLength) + "...";
    }
}
