package cn.ethan.infrastructure.order.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * HTTP 物流网关测试：验证启动阶段拒绝无法安全拼接路径的基础地址。
 *
 * @author ethan
 * @date 2026-08-13
 */
class HttpLogisticsGatewayTest {

    @Test
    void acceptsAbsoluteHttpEndpointAndRejectsUnsafeBaseUrls() {
        assertDoesNotThrow(() -> new HttpLogisticsGateway(
                RestClient.builder(), "https://orders.example.test/api", Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HttpLogisticsGateway(
                RestClient.builder(), "file:///tmp/orders", Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HttpLogisticsGateway(
                RestClient.builder(), "https://orders.example.test?override=true", Duration.ofSeconds(1)
        ));
    }
}
