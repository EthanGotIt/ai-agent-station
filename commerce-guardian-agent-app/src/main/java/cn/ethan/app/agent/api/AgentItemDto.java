package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentItemModel;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 类型职责：表达 Thread Item 的有序事实。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentItemDto(
        String itemId,
        String turnId,
        long sequence,
        String type,
        int schemaVersion,
        String payload,
        Object data,
        Instant createdAt
) {
    private static final Logger LOGGER = Logger.getLogger(AgentItemDto.class.getName());

    public static AgentItemDto from(AgentItemModel item) {
        return from(item, new ObjectMapper());
    }

    public static AgentItemDto from(AgentItemModel item, ObjectMapper objectMapper) {
        return new AgentItemDto(item.itemId(), item.turnId(), item.sequence(), item.kind().name(),
                item.schemaVersion(), item.payloadJson(), decodeData(item, objectMapper), item.createdAt());
    }

    private static Object decodeData(AgentItemModel item, ObjectMapper objectMapper) {
        try {
            JsonNode envelope = objectMapper.readTree(item.payloadJson());
            if (envelope.path("schemaVersion").asInt(-1) == item.schemaVersion()
                    && item.kind().name().equals(envelope.path("kind").asString(""))
                    && !envelope.path("data").isMissingNode()) {
                return envelope.path("data");
            }
        } catch (RuntimeException failure) {
            // 历史坏 payload 仍通过原始 payload 字段返回，新增 data 字段保持可安全降级。
            LOGGER.log(Level.FINE, "Item payload decode failed: itemId=" + item.itemId()
                    + ", type=" + item.kind() + ", errorType=" + failure.getClass().getSimpleName());
        }
        return item.payloadJson();
    }
}
