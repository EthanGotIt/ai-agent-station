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
        if (isCurrentEnvelope(type, value)) {
            return value;
        }
        String data = looksLikeJson(value) ? value : quote(value);
        return "{\"schemaVersion\":1,\"kind\":\"" + type.name()
                + "\",\"data\":" + data + "}";
    }

    /**
     * 转义 JSON 字符串内容，确保所有 ASCII 控制字符都不会破坏 Item envelope。
     */
    public static String escapeJson(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (char current : value.toCharArray()) {
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) current));
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static boolean isCurrentEnvelope(AgentItemTypeEnum type, String value) {
        String compact = value.stripLeading();
        return compact.startsWith("{\"schemaVersion\":1")
                && compact.contains("\"kind\":\"" + type.name() + "\"");
    }

    private static boolean looksLikeJson(String value) {
        String compact = value.strip();
        return (compact.startsWith("{") && compact.endsWith("}"))
                || (compact.startsWith("[") && compact.endsWith("]"))
                || (compact.startsWith("\"") && compact.endsWith("\""));
    }

    private static String quote(String value) {
        return "\"" + escapeJson(value) + "\"";
    }
}
