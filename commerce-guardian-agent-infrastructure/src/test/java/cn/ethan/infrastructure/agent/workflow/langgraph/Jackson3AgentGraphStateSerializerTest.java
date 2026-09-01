package cn.ethan.infrastructure.agent.workflow.langgraph;

import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型职责：验证 LangGraph 状态经过项目 Jackson 3 编解码后保持结构化字段。
 *
 * @author ethan
 * @date 2026-08-27
 */
class Jackson3AgentGraphStateSerializerTest {

    @Test
    void roundTripsStructuredStateWithJackson3() throws Exception {
        Jackson3AgentGraphStateSerializer serializer =
                new Jackson3AgentGraphStateSerializer(new ObjectMapper());
        Map<String, Object> expected = Map.of(
                "workflowVersion", 7,
                "facts", Map.of("orderId", "order-1", "status", "PAID"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            serializer.writeData(expected, output);
        }
        Map<String, Object> restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = serializer.readData(input);
        }

        assertEquals("order-1", ((Map<?, ?>) restored.get("facts")).get("orderId"));
        assertEquals(7, ((Number) restored.get("workflowVersion")).intValue());
    }
}
