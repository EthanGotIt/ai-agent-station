package cn.ethan.ai.domain.agent.model.valobj;

import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单次 Run 的证据工作区，不进入 Session Memory。
 */
@Getter
public class EvidenceBoardVO {

    private final List<Document> evidences = new ArrayList<>();

    private final List<AgenticRagTraceVO.RetrievalRoundVO> retrievalRounds = new ArrayList<>();

    private final Set<String> retrievalKeys = new LinkedHashSet<>();

    private EvidenceAssessmentVO latestAssessment = EvidenceAssessmentVO.builder().build();

    private int externalRetrievalCount;

    public int addEvidence(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return 0;
        }
        Map<String, Document> merged = new LinkedHashMap<>();
        evidences.forEach(document -> merged.put(evidenceKey(document), document));
        int before = merged.size();
        documents.stream().filter(java.util.Objects::nonNull).forEach(document -> {
            String key = evidenceKey(document);
            Document previous = merged.get(key);
            if (previous == null || scoreOf(document) > scoreOf(previous)) {
                merged.put(key, document);
            }
        });
        evidences.clear();
        evidences.addAll(merged.values());
        evidences.sort((left, right) -> Double.compare(scoreOf(right), scoreOf(left)));
        return Math.max(0, merged.size() - before);
    }

    public boolean registerRetrieval(EvidenceSourceTypeEnumVO sourceType, String query) {
        String key = (sourceType == null ? "UNKNOWN" : sourceType.name()) + ":" + normalize(query);
        return retrievalKeys.add(key);
    }

    public void recordRound(AgenticRagTraceVO.RetrievalRoundVO round) {
        if (round != null) {
            retrievalRounds.add(round);
        }
    }

    public void markExternalRetrieval() {
        externalRetrievalCount++;
    }

    public void updateAssessment(EvidenceAssessmentVO assessment) {
        latestAssessment = assessment == null ? EvidenceAssessmentVO.builder().build() : assessment;
    }

    public boolean hasEvidence() {
        return !evidences.isEmpty();
    }

    public List<Document> immutableEvidence() {
        return Collections.unmodifiableList(evidences);
    }

    public String compactObservation() {
        if (evidences.isEmpty()) {
            return "当前没有可归因 evidence。";
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(evidences.size(), 6);
        for (int index = 0; index < limit; index++) {
            Document document = evidences.get(index);
            builder.append("- E").append(index + 1)
                    .append(" source=").append(metadata(document, "qa_evidence_source_type", "qa_retrieval_source"))
                    .append(" title=").append(metadata(document, "title", "source"))
                    .append(" score=").append(String.format(Locale.ROOT, "%.3f", scoreOf(document)))
                    .append(" preview=").append(limit(StringUtils.defaultString(document.getText()), 160))
                    .append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private String evidenceKey(Document document) {
        String uri = metadata(document, "uri", "url", "source_url");
        if (StringUtils.isNotBlank(uri)) {
            return "uri:" + uri;
        }
        if (StringUtils.isNotBlank(document.getId())) {
            return "id:" + document.getId();
        }
        return "text:" + normalize(limit(document.getText(), 240));
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

    private double scoreOf(Document document) {
        return document == null || document.getScore() == null ? 0D : document.getScore();
    }

    private String normalize(String value) {
        return StringUtils.defaultString(value).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String limit(String value, int maxLength) {
        String actual = StringUtils.defaultString(value).trim().replaceAll("\\s+", " ");
        return actual.length() <= maxLength ? actual : actual.substring(0, maxLength) + "...";
    }
}
