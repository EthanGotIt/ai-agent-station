package cn.ethan.core.agent.model;

import java.util.Map;

/**
 * 工具人工确认项：只公开恢复决策所需的工具身份和规范化参数。
 *
 * @author ethan
 * @date 2026-08-09
 */
public record ToolInterventionToolModel(String toolCallId, String toolName, Map<String, String> arguments) {

    public ToolInterventionToolModel {
        if (toolCallId == null || toolCallId.isBlank() || toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool intervention tool is incomplete");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
