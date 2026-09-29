package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.execution.AgentTurnExecutionStateModel;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 类型职责：在数据库边界编解码受控的工具调用恢复记录。
 *
 * @author ethan
 * @date 2026-09-29
 */
@Component
public final class JacksonAgentTurnExecutionStateCodec {

    private final ObjectMapper objectMapper;

    public JacksonAgentTurnExecutionStateCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(List<AgentTurnExecutionStateModel.ToolCall> calls) {
        try {
            return objectMapper.writeValueAsString(calls == null ? List.of() : calls);
        } catch (Exception failure) {
            throw new IllegalStateException("无法编码 Turn 工具调用恢复记录", failure);
        }
    }

    public List<AgentTurnExecutionStateModel.ToolCall> decode(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value,
                    new TypeReference<List<AgentTurnExecutionStateModel.ToolCall>>() { });
        } catch (Exception failure) {
            throw new IllegalStateException("无法解码 Turn 工具调用恢复记录", failure);
        }
    }
}
