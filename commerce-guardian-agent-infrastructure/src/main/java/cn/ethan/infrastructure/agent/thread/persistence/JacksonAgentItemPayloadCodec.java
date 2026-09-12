package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentItemPayloadCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 类型职责：在 Infrastructure 边界统一编解码 Item 的版本化 payload envelope。
 *
 * <p>Core 只传递业务值，不依赖 JSON；历史数据库仍保留 {@code PAYLOAD_JSON} 字符串，
 * 新写入通过此类生成一致的 {@code schemaVersion/kind/data} 结构。</p>
 *
 * @author ethan
 * @date 2026-09-13
 */
@Component
public final class JacksonAgentItemPayloadCodec implements AgentItemPayloadCodec {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public JacksonAgentItemPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 用 Jackson 将结构化业务值包成 Item envelope，避免各个业务适配器手写转义和字段分隔。
     */
    public String encode(AgentItemTypeEnum type, Object data) {
        if (type == null) {
            throw new IllegalArgumentException("Item kind must not be null");
        }
        try {
            ObjectNode envelope = objectMapper.createObjectNode();
            envelope.put("schemaVersion", CURRENT_SCHEMA_VERSION);
            envelope.put("kind", type.name());
            if (data == null) {
                envelope.putNull("data");
            } else {
                envelope.set("data", objectMapper.valueToTree(data));
            }
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Item payload：" + type.name(), failure);
        }
    }

    @Override
    public String encodeJsonText(AgentItemTypeEnum type, String jsonOrText) {
        if (type == null) {
            throw new IllegalArgumentException("Item kind must not be null");
        }
        String value = jsonOrText == null ? "" : jsonOrText;
        try {
            JsonNode parsed = null;
            try {
                parsed = objectMapper.readTree(value);
            } catch (Exception legacyParseFailure) {
                // 旧调用方可能传入未结构化文本；按字符串数据安全编码，不让坏 JSON 穿透边界。
                // 不记录原始内容，避免把用户输入或敏感业务字段写入日志。
                parsed = null;
            }
            if (parsed != null && parsed.isObject()
                    && parsed.path("schemaVersion").asInt(-1) == CURRENT_SCHEMA_VERSION
                    && type.name().equals(parsed.path("kind").asString(""))
                    && !parsed.path("data").isMissingNode()) {
                return objectMapper.writeValueAsString(parsed);
            }
            JsonNode data = parsed == null ? objectMapper.valueToTree(value) : parsed;
            return encodeNode(type, data);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Item payload：" + type.name(), failure);
        }
    }

    /**
     * 解码并校验 envelope 的版本和类型；调用方只能取得 data 节点，不能绕过类型边界读取外层字段。
     */
    public JsonNode decode(AgentItemTypeEnum expectedType, String payload) {
        if (expectedType == null || payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Item kind and payload must not be blank");
        }
        try {
            JsonNode envelope = objectMapper.readTree(payload);
            int schemaVersion = envelope.path("schemaVersion").asInt(-1);
            String kind = envelope.path("kind").asString("");
            if (schemaVersion != CURRENT_SCHEMA_VERSION || !expectedType.name().equals(kind)
                    || envelope.path("data").isMissingNode()) {
                throw new IllegalArgumentException("Item payload schema or kind is invalid");
            }
            return envelope.path("data");
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Item payload is not valid JSON", failure);
        }
    }

    private String encodeNode(AgentItemTypeEnum type, JsonNode data) throws Exception {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schemaVersion", CURRENT_SCHEMA_VERSION);
        envelope.put("kind", type.name());
        if (data == null) {
            envelope.putNull("data");
        } else {
            envelope.set("data", data);
        }
        return objectMapper.writeValueAsString(envelope);
    }
}
