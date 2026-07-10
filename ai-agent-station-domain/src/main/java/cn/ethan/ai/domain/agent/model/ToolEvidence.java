package cn.ethan.ai.domain.agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 由已批准的只读工具返回的标准化证据。
 */
public record ToolEvidence(String toolName, Map<String, Object> fields) {

    public ToolEvidence {
        fields = fields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fields));
    }
}
