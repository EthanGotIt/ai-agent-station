package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达受控错误事实，避免把错误码和用户可见说明拼接成不可解析文本。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentErrorPayloadModel(
        String code,
        String message
) implements AgentItemPayloadValue {

    public AgentErrorPayloadModel {
        code = required(code, "code");
        message = optional(message);
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.ERROR;
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
