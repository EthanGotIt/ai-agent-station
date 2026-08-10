package cn.ethan.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话记忆参数测试：验证双开关默认关闭和旧开关兼容边界。
 *
 * @author ethan
 * @date 2026-08-10
 */
class AgentMemoryPropertiesTest {

    @Test
    void defaultsBothGenerationAndUsageToDisabled() {
        AgentMemoryProperties properties = new AgentMemoryProperties(null, null, null, null, null, null);

        assertFalse(properties.generationEnabled());
        assertFalse(properties.usageEnabled());
        assertEquals(Duration.ofSeconds(30), properties.idleDelay());
        assertEquals(64, properties.extractionQueueCapacity());
        assertEquals(0.75, properties.minimumAutoConfidence());
    }

    @Test
    void legacyRecordingAliasEnablesGenerationOnly() {
        AgentMemoryProperties properties = new AgentMemoryProperties(
                null, null, true, null, null, null
        );

        assertTrue(properties.generationEnabled());
        assertFalse(properties.usageEnabled());
    }
}
