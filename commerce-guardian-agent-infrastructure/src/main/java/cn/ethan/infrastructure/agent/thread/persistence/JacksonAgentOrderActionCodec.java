package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 类型职责：在持久化边界编码和恢复订单动作输入。
 *
 * @author ethan
 * @date 2026-08-24
 */
@Component
public final class JacksonAgentOrderActionCodec {

    private final ObjectMapper objectMapper;

    public JacksonAgentOrderActionCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(AgentOrderActionInput input) {
        if (input == null) {
            return null;
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("sourceTurnId", input.sourceTurnId());
        root.put("orderId", input.orderId());
        root.put("actionType", input.actionType().name());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码订单动作输入", failure);
        }
    }

    public AgentOrderActionInput decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            return new AgentOrderActionInput(
                    root.path("sourceTurnId").asString(),
                    root.path("orderId").asString(),
                    AgentOrderActionTypeEnum.valueOf(root.path("actionType").asString()));
        } catch (Exception failure) {
            throw new IllegalStateException("无法解码订单动作输入", failure);
        }
    }
}
