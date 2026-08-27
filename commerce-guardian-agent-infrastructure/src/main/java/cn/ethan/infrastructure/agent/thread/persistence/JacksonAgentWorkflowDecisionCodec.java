package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 类型职责：在 Turn 持久化边界编码和恢复 Workflow Checkpoint 决策输入。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public final class JacksonAgentWorkflowDecisionCodec {

    private final ObjectMapper objectMapper;

    public JacksonAgentWorkflowDecisionCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(AgentWorkflowDecisionInput input) {
        if (input == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Workflow Checkpoint 决策", failure);
        }
    }

    public AgentWorkflowDecisionInput decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            return new AgentWorkflowDecisionInput(
                    root.path("runId").asString(),
                    root.path("checkpointId").asString(),
                    root.path("expectedVersion").asLong(-1),
                    AgentWorkflowDecisionEnum.valueOf(root.path("decision").asString()),
                    root.path("factsFingerprint").asString(""));
        } catch (Exception failure) {
            throw new IllegalStateException("无法解码 Workflow Checkpoint 决策", failure);
        }
    }
}
