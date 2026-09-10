package cn.ethan.infrastructure.commerce.order.http;

import cn.ethan.infrastructure.http.FakeClientHttpRequestFactoryTest;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
                RestClient.builder(), "https://orders.example.test/api", Duration.ofSeconds(1),
                fakeTransport()));
        assertThrows(IllegalArgumentException.class, () -> new HttpLogisticsGateway(
                RestClient.builder(), "file:///tmp/orders", Duration.ofSeconds(1), fakeTransport()
        ));
        assertThrows(IllegalArgumentException.class, () -> new HttpLogisticsGateway(
                RestClient.builder(), "https://orders.example.test?override=true", Duration.ofSeconds(1),
                fakeTransport()
        ));
    }

    @Test
    void ignoresNullEventsWithoutDiscardingValidTraceEntries() {
        FakeClientHttpRequestFactoryTest transport = new FakeClientHttpRequestFactoryTest(request ->
                FakeClientHttpRequestFactoryTest.Response.json(200, """
                        [null, {"eventId":"EVENT-001","status":"IN_TRANSIT",
                        "location":"上海","description":"已发出","occurredAt":"2026-08-27T00:00:00Z"}]
                        """));

        var trace = new HttpLogisticsGateway(
                RestClient.builder(), "https://orders.example.test/api", Duration.ofSeconds(1), transport)
                .findTrace("ORDER-001", "user-1");

        assertEquals(1, trace.size());
        assertEquals("EVENT-001", trace.get(0).eventId());
        assertEquals("/api/orders/ORDER-001/logistics", transport.requests().get(0).uri().getPath());
    }

    @Test
    void skipsMalformedEventsWithoutDiscardingValidTraceEntries() {
        FakeClientHttpRequestFactoryTest transport = new FakeClientHttpRequestFactoryTest(request ->
                FakeClientHttpRequestFactoryTest.Response.json(200, """
                        [
                          {"eventId":"EVENT-BAD","status":"IN_TRANSIT","description":" "},
                          {"eventId":"EVENT-OK","status":"IN_TRANSIT","description":"已发出",
                           "occurredAt":"2026-08-27T00:00:00Z"}
                        ]
                        """));

        var trace = new HttpLogisticsGateway(
                RestClient.builder(), "https://orders.example.test/api", Duration.ofSeconds(1), transport)
                .findTrace(" ORDER-001 ", " user-1 ");

        assertEquals(1, trace.size());
        assertEquals("EVENT-OK", trace.get(0).eventId());
        assertEquals("user-1", transport.requests().get(0).headers().getFirst("X-User-Id"));
    }

    private FakeClientHttpRequestFactoryTest fakeTransport() {
        return new FakeClientHttpRequestFactoryTest(request ->
                FakeClientHttpRequestFactoryTest.Response.json(200, "[]"));
    }
}
