package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.coordination.AgentContinuationInput;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * 类型职责：在 Turn 持久化边界编码和恢复 Agent 续跑输入。
 *
 * @author ethan
 * @date 2026-08-26
 */
@Component
public final class JacksonAgentContinuationCodec {

    private final ObjectMapper objectMapper;

    public JacksonAgentContinuationCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(AgentContinuationInput input) {
        if (input == null) {
            return null;
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("rootTurnId", input.rootTurnId());
        root.put("parentTurnId", input.parentTurnId());
        root.put("triggerRunId", input.triggerRunId());
        if (input.triggerCommandId() != null) {
            root.put("triggerCommandId", input.triggerCommandId());
        }
        root.put("triggerStatus", input.triggerStatus());
        root.put("triggerSequence", input.triggerSequence());
        root.put("cycleNo", input.cycleNo());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Agent 续跑输入", failure);
        }
    }

    public AgentContinuationInput decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            String commandId = root.path("triggerCommandId").asString(null);
            return new AgentContinuationInput(
                    root.path("rootTurnId").asString(),
                    root.path("parentTurnId").asString(),
                    root.path("triggerRunId").asString(),
                    commandId,
                    root.path("triggerStatus").asString(),
                    root.path("triggerSequence").asLong(0L),
                    root.path("cycleNo").asInt(0));
        } catch (Exception failure) {
            throw new IllegalStateException("无法解码 Agent 续跑输入", failure);
        }
    }
}
