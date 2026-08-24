package cn.ethan.infrastructure.agent.workflow.transaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类型职责：构造 QuestionCard 的受控字段和摘要 schema，不参与 Workflow 状态推进或事务操作。
 *
 * @author ethan
 * @date 2026-08-24
 */
public final class AgentWorkflowQuestionSchema {

    private AgentWorkflowQuestionSchema() {
    }

    public static Map<String, Object> field(
            String name,
            String label,
            String type,
            boolean required,
            int maxLength,
            List<String> options,
            boolean allowCustom
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("label", label);
        value.put("type", type);
        value.put("required", required);
        value.put("maxLength", maxLength);
        value.put("options", options);
        value.put("allowCustom", allowCustom);
        return value;
    }

    public static Map<String, String> summary(String label, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String bounded = value.length() <= 256 ? value : value.substring(0, 256);
        return Map.of("label", label, "value", bounded);
    }
}
