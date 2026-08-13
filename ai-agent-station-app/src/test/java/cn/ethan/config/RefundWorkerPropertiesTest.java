package cn.ethan.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 退款任务配置测试：验证安全默认值与有限重试参数边界。
 *
 * @author ethan
 * @date 2026-08-12
 */
class RefundWorkerPropertiesTest {

    @Test
    void appliesBoundedDefaults() {
        RefundWorkerProperties properties = new RefundWorkerProperties(null, null, null, null, null, null);

        assertEquals(Duration.ofSeconds(5), properties.pollInterval());
        assertEquals(8, properties.batchSize());
        assertEquals(3, properties.maxAttempts());
    }

    @Test
    void rejectsUnboundedPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new RefundWorkerProperties(
                Duration.ofSeconds(5), Duration.ofSeconds(10), 0, 3,
                Duration.ofSeconds(15), Duration.ofSeconds(30)
        ));
    }
}
