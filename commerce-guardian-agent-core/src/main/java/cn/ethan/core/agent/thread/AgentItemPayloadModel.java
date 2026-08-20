package cn.ethan.core.agent.thread;

/**
 * 类型职责：构造可跨版本传输的 Item 判别 JSON，限制公开事实只包含受控数据。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentItemPayloadModel(
        int schemaVersion,
        String kind,
        String data
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AgentItemPayloadModel {
        if (schemaVersion != CURRENT_SCHEMA_VERSION || kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("Item payload schema or kind is invalid");
        }
        data = data == null ? "" : data;
    }

    /**
     * 将历史调用方传入的文本或 JSON 数据包成稳定 envelope；已包裹数据保持原样以支持幂等重试。
     */
    public static String ensure(AgentItemTypeEnum type, String payload) {
        if (type == null) {
            throw new IllegalArgumentException("Item kind must not be null");
        }
        String value = payload == null ? "" : payload;
        if (isCurrentEnvelope(value)) {
            return value;
        }
        String data = looksLikeJson(value) ? value : quote(value);
        return "{\"schemaVersion\":1,\"kind\":\"" + type.name()
                + "\",\"data\":" + data + "}";
    }

    private static boolean isCurrentEnvelope(String value) {
        String compact = value.stripLeading();
        return compact.startsWith("{\"schemaVersion\":1") && compact.contains("\"kind\"");
    }

    private static boolean looksLikeJson(String value) {
        String compact = value.strip();
        return (compact.startsWith("{") && compact.endsWith("}"))
                || (compact.startsWith("[") && compact.endsWith("]"))
                || (compact.startsWith("\"") && compact.endsWith("\""));
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t") + "\"";
    }
}
