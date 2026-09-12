package cn.ethan.core.agent.thread;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 类型职责：表达一次受控 Tool 调用及其稳定关联标识。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentToolCallPayloadModel(
        String tool,
        String invocationId,
        String toolBatchId,
        Map<String, String> arguments
) implements AgentItemPayloadValue {

    public AgentToolCallPayloadModel {
        tool = required(tool, "tool");
        invocationId = required(invocationId, "invocationId");
        toolBatchId = optional(toolBatchId);
        arguments = copyArguments(arguments);
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.TOOL_CALL;
    }

    private static Map<String, String> copyArguments(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                throw new IllegalArgumentException("Tool 参数名和值不能为空");
            }
            copy.put(key.trim(), value);
        });
        return java.util.Collections.unmodifiableMap(copy);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
