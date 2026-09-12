package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;

/**
 * 类型职责：表达订单动作请求事实，确保来源 Turn、订单和动作类型使用同一受控值模型。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentOrderActionPayloadModel(
        String sourceTurnId,
        String orderId,
        AgentOrderActionTypeEnum actionType
) implements AgentItemPayloadValue {

    public AgentOrderActionPayloadModel {
        sourceTurnId = required(sourceTurnId, "sourceTurnId");
        orderId = required(orderId, "orderId");
        if (actionType == null) {
            throw new IllegalArgumentException("actionType must not be null");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.ORDER_ACTION_REQUEST;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }
}
