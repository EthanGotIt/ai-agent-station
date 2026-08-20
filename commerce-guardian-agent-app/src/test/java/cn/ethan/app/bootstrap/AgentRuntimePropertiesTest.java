package cn.ethan.app.bootstrap;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Agent Runtime 参数测试：验证安全默认值和资源上限在启动前生效。
 *
 * @author ethan
 * @date 2026-08-19
 */
class AgentRuntimePropertiesTest {

    @Test
    void appliesSafeDefaults() {
        AgentRuntimeProperties properties = new AgentRuntimeProperties(
                null,
                null,
                null
        );

        assertEquals(Duration.ofSeconds(245), properties.streamTimeout());
        assertEquals(Duration.ofSeconds(15), properties.heartbeatInterval());
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
                () -> new AgentRuntimeProperties.ExecutorProperties(
                        8,
                        4,
                        100,
                        Duration.ofSeconds(10)
                )
        );
    }

    @Test
    void rejectsExcessiveStreamTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRuntimeProperties(
                        Duration.ofMinutes(6),
                        null,
                        null
                )
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
}
