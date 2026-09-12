package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达已校验归属的物流事件，作为物流时间线的受控嵌套值。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentLogisticsEventPayloadModel(
        String eventId,
        String status,
        String location,
        String description,
        String occurredAt
) {

    public AgentLogisticsEventPayloadModel {
        eventId = required(eventId, "eventId");
        status = required(status, "status");
        location = location == null ? "" : location.trim();
        description = required(description, "description");
        occurredAt = required(occurredAt, "occurredAt");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
