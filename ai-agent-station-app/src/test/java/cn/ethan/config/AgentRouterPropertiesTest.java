package cn.ethan.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由模型配置测试：验证 Flash Thinking 的默认预算与上限。
 *
 * @author ethan
 * @date 2026-08-06
 */
class AgentRouterPropertiesTest {

    @Test
    void enablesBoundedThinkingByDefault() {
        AgentRouterProperties properties = new AgentRouterProperties(null, null, null, null);

        assertTrue(properties.thinkingEnabled());
        assertEquals(512, properties.thinkingBudget());
        assertEquals(6, properties.historyTurns());
    }

    @Test
    void rejectsExcessiveThinkingBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRouterProperties(true, 2_049, null, null)
        );
    }
}
