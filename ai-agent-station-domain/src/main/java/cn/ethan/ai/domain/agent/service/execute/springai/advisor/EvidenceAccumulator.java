package cn.ethan.ai.domain.agent.service.execute.springai.advisor;

import cn.ethan.ai.domain.agent.model.valobj.RagEvidenceVO;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run 级 evidence 收集器，供 Spring AI Advisor 链使用。
 */
public class EvidenceAccumulator {

    private final Map<String, RagEvidenceVO> evidences = new LinkedHashMap<>();

    public int addDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (Document document : documents) {
            RagEvidenceVO evidence = toEvidence(document, evidences.size() + 1);
            if (StringUtils.isBlank(evidence.getContentPreview())) {
                continue;
            }
            String key = evidenceKey(evidence);
            if (!evidences.containsKey(key)) {
                evidences.put(key, evidence);
                added++;
            }
        }
        return added;
    }

    public List<RagEvidenceVO> snapshot() {
        return new ArrayList<>(evidences.values());
    }

    public boolean isEmpty() {
        return evidences.isEmpty();
    }

    private RagEvidenceVO toEvidence(Document document, int index) {
        Map<String, Object> metadata = document.getMetadata();
        String uri = firstPresent(metadata, "uri", "url", "source_url", "qa_source_uri");
        String title = firstPresent(metadata, "title", "source", "qa_source_title");
        String sourceType = firstPresent(metadata, "qa_evidence_source_type", "source_type");
        String retrievalSource = firstPresent(metadata, "qa_retrieval_source");
        String toolName = firstPresent(metadata, "tool_name", "qa_tool_name");
        return RagEvidenceVO.builder()
                .evidenceId("E" + index)
                .sourceType(sourceType)
                .sourceName(StringUtils.defaultIfBlank(title, retrievalSource))
                .uri(uri)
                .toolName(toolName)
                .score(document.getScore())
                .contentPreview(limit(document.getText(), 800))
                .retrievedAt(LocalDateTime.now().toString())
                .build();
    }

    private String evidenceKey(RagEvidenceVO evidence) {
        if (StringUtils.isNotBlank(evidence.getUri())) {
            return "uri:" + evidence.getUri();
        }
        if (StringUtils.isNotBlank(evidence.getSourceName())) {
            return "title:" + evidence.getSourceName() + ":" + evidence.getContentPreview();
        }
        return "content:" + evidence.getContentPreview();
    }

    private String firstPresent(Map<String, Object> metadata, String... keys) {
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null && StringUtils.isNotBlank(value.toString())) {
                return value.toString();
            }
        }
        return "";
    }

    private String limit(String content, int maxLength) {
        String value = StringUtils.defaultString(content);
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }
}
