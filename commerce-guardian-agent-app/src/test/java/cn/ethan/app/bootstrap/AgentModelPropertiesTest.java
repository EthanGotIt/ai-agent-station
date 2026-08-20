package cn.ethan.app.bootstrap;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agent 模型参数测试：验证 thinking 禁用和远程调用资源上限。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentModelPropertiesTest {

    @Test
    void appliesDeepSeekDefaults() {
        AgentModelProperties properties = new AgentModelProperties(null, null, null, null);

        assertEquals("deepseek-chat", properties.name());
        assertEquals(1024, properties.maxOutputTokens());
        assertEquals(1, properties.maxAttempts());
        assertEquals(Duration.ofSeconds(30), properties.httpTimeout());
    }

    @Test
    void rejectsThinkingModel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentModelProperties("deepseek-reasoner", 1024, 1, Duration.ofSeconds(30))
        );
    }

    @Test
    void rejectsUnboundedOutputBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentModelProperties("deepseek-chat", 8193, 1, Duration.ofSeconds(30))
        );
    }

    @Test
    void rejectsExcessiveHttpTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentModelProperties("deepseek-chat", 1024, 1, Duration.ofMinutes(6))
        );
    }
}
