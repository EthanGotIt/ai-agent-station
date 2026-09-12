package cn.ethan.core.agent.thread;

import java.util.List;

/**
 * 类型职责：表达一个订单的物流时间线事实。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentLogisticsTimelinePayloadModel(
        String orderId,
        List<AgentLogisticsEventPayloadModel> events
) implements AgentItemPayloadValue {

    public AgentLogisticsTimelinePayloadModel {
        orderId = required(orderId, "orderId");
        events = events == null ? List.of() : List.copyOf(events);
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.LOGISTICS_TIMELINE;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
