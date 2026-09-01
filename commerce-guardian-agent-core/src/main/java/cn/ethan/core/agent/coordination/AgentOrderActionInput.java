package cn.ethan.core.agent.coordination;

/**
 * 订单卡片动作的结构化输入；与 Turn 一起持久化，支持重启后不依赖前端文本恢复。
 *
 * @author ethan
 * @date 2026-08-24
 */
public record AgentOrderActionInput(
        String sourceTurnId,
        String orderId,
        AgentOrderActionTypeEnum actionType
) {

    public static final int MAX_ORDER_ID_LENGTH = 128;

    public AgentOrderActionInput {
        sourceTurnId = normalize(sourceTurnId, "sourceTurnId", 64);
        orderId = normalize(orderId, "orderId", MAX_ORDER_ID_LENGTH);
        if (actionType == null) {
            throw new IllegalArgumentException("actionType 不能为空");
        }
    }

    private static String normalize(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }
}
