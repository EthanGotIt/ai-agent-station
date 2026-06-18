package cn.ethan.ai.domain.agent.service.execute.harness;

import cn.ethan.ai.domain.agent.model.valobj.AgentActionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentActionTypeEnumVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 解析模型输出的 Harness action JSON。
 */
@Service
public class AgentActionParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentActionVO parse(String rawText, String fallbackQuery) {
        String normalizedText = StringUtils.defaultString(rawText).trim();
        if (StringUtils.isBlank(normalizedText)) {
            return fallbackAction(fallbackQuery, rawText, "模型未输出 action JSON");
        }

        try {
            JsonNode root = objectMapper.readTree(extractJson(normalizedText));
            String actionType = firstText(root, "actionType", "action", "type");
            AgentActionTypeEnumVO type = AgentActionTypeEnumVO.from(actionType);
            if (type == null) {
                return fallbackAction(fallbackQuery, rawText, "未知 actionType：" + actionType);
            }
            Map<String, Object> args = objectMapper.convertValue(root, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return AgentActionVO.builder()
                    .actionId(StringUtils.defaultIfBlank(firstText(root, "actionId", "id"), type.name().toLowerCase()))
                    .type(type)
                    .query(StringUtils.defaultIfBlank(firstText(root, "query", "question", "input"), fallbackQuery))
                    .reason(firstText(root, "reason", "rationale"))
                    .answer(firstText(root, "answer", "content", "finalAnswer"))
                    .args(args)
                    .rawText(rawText)
                    .build();
        } catch (Exception e) {
            return fallbackAction(fallbackQuery, rawText, "action JSON 解析失败：" + e.getMessage());
        }
    }

    private AgentActionVO fallbackAction(String fallbackQuery, String rawText, String parseError) {
        return AgentActionVO.builder()
                .actionId("llm_respond")
                .type(AgentActionTypeEnumVO.LLM_RESPOND)
                .query(fallbackQuery)
                .reason("模型 action 输出不可解析，降级为直接回答。")
                .rawText(rawText)
                .parseError(parseError)
                .build();
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
