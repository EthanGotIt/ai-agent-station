package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达一次受控 Tool 返回的可审计结果字段。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentToolResultPayloadModel(
        String tool,
        String invocationId,
        String toolBatchId,
        String status,
        String result,
        boolean truncated
) implements AgentItemPayloadValue {

    public AgentToolResultPayloadModel {
        tool = required(tool, "tool");
        invocationId = required(invocationId, "invocationId");
        toolBatchId = optional(toolBatchId);
        status = status == null ? "" : status.trim();
        result = result == null ? "" : result;
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.TOOL_RESULT;
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
