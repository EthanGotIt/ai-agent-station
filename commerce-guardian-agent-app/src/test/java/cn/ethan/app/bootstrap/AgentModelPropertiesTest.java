package cn.ethan.app.bootstrap;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agent 启动参数测试：验证模型配置、Runtime 默认值和远程调用资源上限。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentModelPropertiesTest {

    @Test
    void appliesDeepSeekDefaults() {
        AgentModelProperties properties = new AgentModelProperties(null, null, null, null);

        assertEquals("deepseek-v4-pro", properties.name());
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
                () -> new AgentModelProperties("deepseek-v4-pro", 8193, 1, Duration.ofSeconds(30))
        );
    }

    @Test
    void rejectsExcessiveHttpTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentModelProperties("deepseek-v4-pro", 1024, 1, Duration.ofMinutes(6))
        );
    }

    @Test
    void appliesSafeRuntimeDefaults() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties(null, null, null);

        assertEquals(Duration.ofSeconds(245), properties.streamTimeout());
        assertEquals(Duration.ofSeconds(15), properties.heartbeatInterval());
        assertEquals(Boolean.TRUE, properties.continuationEnabled());
        assertEquals(3, properties.maxAgentCycles());
        assertEquals(8192, properties.maxOutputTokensPerTurn());
        assertEquals(3, properties.repeatedToolFailureThreshold());
        assertEquals(4, properties.queue().maxPendingPerThread());
        assertEquals(256, properties.queue().maxPendingGlobal());
        assertEquals(Duration.ofMinutes(2), properties.queue().waitTimeout());
        assertEquals(4, properties.executor().corePoolSize());
        assertEquals(16, properties.executor().maxPoolSize());
        assertEquals(256, properties.executor().queueCapacity());
    }

    @Test
    void rejectsExecutorMaximumBelowCoreSize() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties.ExecutorProperties(8, 4, 100, Duration.ofSeconds(10))
        );
    }

    @Test
    void rejectsExcessiveStreamTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties(Duration.ofMinutes(6), null, null)
        );
    }

    @Test
    void rejectsExecutorQueueSmallerThanGlobalPendingLimit() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties(
                        null,
                        new AgentRuntimeProperties.QueueProperties(4, 256, null),
                        new AgentRuntimeProperties.ExecutorProperties(4, 16, 128, null)
                )
        );
    }

    @Test
    void rejectsContinuationCycleOutsideSafeBound() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties(null, null, null, null, true, 6, null, null)
        );
    }

    @Test
    void rejectsInvalidRuntimeOutputBudgetAndFailureThreshold() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties(null, null, null, null, true, 3, 0, 3)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties(null, null, null, null, true, 3, 8192, 21)
        );
    }
}
