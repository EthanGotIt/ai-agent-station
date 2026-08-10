package cn.ethan.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentScope ReAct 参数测试：验证思考与执行边界默认值。
 *
 * @author ethan
 * @date 2026-08-06
 */
class AgentScopeReActPropertiesTest {

    @Test
    void appliesPlusReactSafetyDefaults() {
        AgentScopeReActProperties properties = new AgentScopeReActProperties(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(Duration.ofSeconds(120), properties.timeout());
        assertEquals(8, properties.maxIterations());
        assertEquals(1, properties.maxRetries());
        assertTrue(properties.thinkingEnabled());
        assertEquals(4_096, properties.thinkingBudget());
    }

    @Test
    void rejectsExcessiveThinkingBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentScopeReActProperties(
                        "key",
                        null,
                        Duration.ofSeconds(120),
                        8,
                        2_048,
                        1,
                        true,
                        65_537
                )
        );
    }
}
