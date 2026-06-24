package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.EvidenceAssessmentVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.EvidenceSourceTypeEnumVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 解析模型输出的 Harness action JSON。
 */
@Service
public class AgentActionParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentActionVO parse(String rawText, String fallbackQuery) {
        return parse(rawText, fallbackQuery, false);
    }

    public AgentActionVO parse(String rawText, String fallbackQuery, boolean hasEvidence) {
        String normalizedText = StringUtils.defaultString(rawText).trim();
        if (StringUtils.isBlank(normalizedText)) {
            return fallbackAction(fallbackQuery, rawText, "模型未输出 action JSON", hasEvidence);
        }

        try {
            JsonNode root = objectMapper.readTree(extractJson(normalizedText));
            String actionType = firstText(root, "actionType", "action", "type");
            AgentActionTypeEnumVO type = AgentActionTypeEnumVO.from(actionType);
            if (type == null) {
                return fallbackAction(fallbackQuery, rawText, "未知 actionType：" + actionType, hasEvidence);
            }
            List<String> queries = readQueries(root, fallbackQuery);
            Map<String, Object> args = objectMapper.convertValue(root, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return AgentActionVO.builder()
                    .actionId(StringUtils.defaultIfBlank(firstText(root, "actionId", "id"), type.name().toLowerCase()))
                    .type(type)
                    .query(queries.isEmpty() ? fallbackQuery : queries.get(0))
                    .queries(queries)
                    .sourceType(EvidenceSourceTypeEnumVO.from(firstText(root, "sourceType", "source")))
                    .reason(firstText(root, "reason", "rationale"))
                    .clarifyingQuestion(firstText(root, "clarifyingQuestion", "questionToUser", "answer"))
                    .assessment(readAssessment(root.path("assessment")))
                    .args(args)
                    .rawText(rawText)
                    .build();
        } catch (Exception e) {
            return fallbackAction(fallbackQuery, rawText, "action JSON 解析失败：" + e.getMessage(), hasEvidence);
        }
    }

    private AgentActionVO fallbackAction(String fallbackQuery, String rawText, String parseError, boolean hasEvidence) {
        AgentActionTypeEnumVO type = hasEvidence ? AgentActionTypeEnumVO.FINALIZE : resolveInitialFallback(fallbackQuery);
        return AgentActionVO.builder()
                .actionId("safe_fallback")
                .type(type)
                .query(fallbackQuery)
                .queries(type == AgentActionTypeEnumVO.RETRIEVE ? List.of(fallbackQuery) : List.of())
                .sourceType(type == AgentActionTypeEnumVO.RETRIEVE ? fallbackSource(fallbackQuery) : null)
                .reason(hasEvidence ? "Action 不可解析，基于已有 evidence 安全收口。" : "Action 不可解析，使用确定性安全路由。")
                .rawText(rawText)
                .parseError(parseError)
                .build();
    }

    private AgentActionTypeEnumVO resolveInitialFallback(String query) {
        String normalized = StringUtils.defaultString(query).toLowerCase();
        if (containsAny(normalized, "润色", "改写", "翻译", "写一段", "生成文案")) {
            return AgentActionTypeEnumVO.FINALIZE;
        }
        return AgentActionTypeEnumVO.RETRIEVE;
    }

    private EvidenceSourceTypeEnumVO fallbackSource(String query) {
        String normalized = StringUtils.defaultString(query).toLowerCase();
        if (containsAny(normalized, "最新", "联网", "搜索", "调研")) {
            return EvidenceSourceTypeEnumVO.WEB_RESEARCH;
        }
        if (containsAny(normalized, "官方", "官网", "文档", "版本")) {
            return EvidenceSourceTypeEnumVO.OFFICIAL_DOCS;
        }
        return EvidenceSourceTypeEnumVO.PROJECT_KNOWLEDGE;
    }

    private List<String> readQueries(JsonNode root, String fallbackQuery) {
        List<String> queries = new ArrayList<>();
        JsonNode values = root.path("queries");
        if (values.isArray()) {
            values.forEach(value -> {
                if (value.isTextual() && StringUtils.isNotBlank(value.asText()) && queries.size() < 2) {
                    queries.add(value.asText().trim());
                }
            });
        }
        String single = firstText(root, "query", "question", "input");
        if (queries.isEmpty() && StringUtils.isNotBlank(single)) {
            queries.add(single);
        }
        if (queries.isEmpty() && StringUtils.isNotBlank(fallbackQuery)) {
            queries.add(fallbackQuery);
        }
        return queries;
    }

    private EvidenceAssessmentVO readAssessment(JsonNode assessment) {
        if (assessment == null || !assessment.isObject()) {
            return EvidenceAssessmentVO.builder().build();
        }
        List<String> gaps = new ArrayList<>();
        if (assessment.path("gaps").isArray()) {
            assessment.path("gaps").forEach(value -> {
                if (value.isTextual() && StringUtils.isNotBlank(value.asText())) {
                    gaps.add(value.asText().trim());
                }
            });
        }
        return EvidenceAssessmentVO.builder()
                .sufficient(assessment.path("sufficient").asBoolean(false))
                .coverage(clamp(assessment.path("coverage").asDouble(0D)))
                .confidence(clamp(assessment.path("confidence").asDouble(0D)))
                .gaps(gaps)
                .reason(firstText(assessment, "reason"))
                .build();
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String extractJson(String text) {
        String withoutFence = text.replace("```json", "").replace("```", "").trim();
        int start = withoutFence.indexOf('{');
        int end = withoutFence.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return withoutFence.substring(start, end + 1);
        }
        return withoutFence;
    }

    private String firstText(JsonNode root, String... fieldNames) {
        if (root == null || fieldNames == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            JsonNode value = root.get(fieldName);
            if (value != null && value.isValueNode() && StringUtils.isNotBlank(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }
}
