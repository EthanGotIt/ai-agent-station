package cn.ethan.infrastructure.agent.workflow.langgraph;

import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Map;

/**
 * 类型职责：使用项目 Jackson 3 将 LangGraph 状态编码为可移植 JSON。
 *
 * @author ethan
 * @date 2026-08-27
 */
public final class Jackson3AgentGraphStateSerializer extends StateSerializer<AgentState> {

    private static final TypeReference<Map<String, Object>> STATE_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public Jackson3AgentGraphStateSerializer(ObjectMapper objectMapper) {
        super(AgentState::new);
        this.objectMapper = objectMapper;
    }

    @Override
    public void writeData(Map<String, Object> data, ObjectOutput output) throws IOException {
        output.writeObject(objectMapper.writeValueAsString(data));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> readData(ObjectInput input) throws IOException, ClassNotFoundException {
        Object encoded = input.readObject();
        if (!(encoded instanceof String json)) {
            throw new IOException("LangGraph 状态快照不是 JSON 字符串");
        }
        return objectMapper.readValue(json, STATE_TYPE);
    }
}
