package cn.ethan.infrastructure.commerce.order.http;

import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import cn.ethan.infrastructure.http.FakeClientHttpRequestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 订单网关单元测试：使用内存 Transport 验证协议映射，不依赖 loopback 监听。
 *
 * @author ethan
 * @date 2026-08-27
 */
class HttpOrderGatewayTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void mapsOwnedOrderAndPropagatesUserHeader() {
        FakeClientHttpRequestFactory transport = new FakeClientHttpRequestFactory(request ->
                FakeClientHttpRequestFactory.Response.json(200, """
                        {"accessDenied":false,"orderId":"ORDER-001","userId":"user-1","status":"PAID"}
                        """));

        var result = gateway(transport).findOrder("ORDER-001", "user-1");

        assertEquals(OrderLookupStatusEnum.FOUND, result.status());
        assertEquals("user-1", result.order().userId());
        assertEquals("user-1", transport.requests().get(0).headers().getFirst("X-User-Id"));
        assertEquals("/orders/ORDER-001", transport.requests().get(0).uri().getPath());
    }

    @Test
    void rejectsResponseOwnedByAnotherUser() {
        FakeClientHttpRequestFactory transport = new FakeClientHttpRequestFactory(request ->
                FakeClientHttpRequestFactory.Response.json(200,
                        "{\"orderId\":\"ORDER-001\",\"userId\":\"user-2\",\"status\":\"PAID\"}"));

        assertEquals(OrderLookupStatusEnum.ACCESS_DENIED,
                gateway(transport).findOrder("ORDER-001", "user-1").status());
    }

    @Test
    void mapsSearchAndActionContractsWithStructuredHeaders() {
        FakeClientHttpRequestFactory transport = new FakeClientHttpRequestFactory(request -> {
            if (request.uri().getPath().endsWith("/search")) {
                return FakeClientHttpRequestFactory.Response.json(200,
                        "[{\"orderId\":\"ORDER-001\",\"userId\":\"user-1\","
                                + "\"status\":\"PAID\",\"itemSummary\":\"无线耳机\"}]");
            }
            return FakeClientHttpRequestFactory.Response.json(200,
                    "{\"success\":true,\"retryable\":false,\"code\":\"OK\",\"message\":\"done\"}");
        });
        HttpOrderGateway gateway = gateway(transport);

        OrderSearchCriteria criteria = new OrderSearchCriteria(
                NOW.minusSeconds(3600), NOW, new BigDecimal("50"), new BigDecimal("120"),
                Set.of(OrderStatusEnum.PAID), "耳机", 3, OrderVisibilityEnum.ACTIVE, 5);
        var search = gateway.searchOrders(criteria, "user-1");
        var action = gateway.expedite("user-1", "ORDER-001", "action-1", NOW);

        assertEquals(OrderSearchStatusEnum.SUCCESS, search.status());
        assertEquals("ORDER-001", search.orders().get(0).orderId());
        assertEquals("OK", action.code());
        assertEquals("action-1", transport.requests().get(1).headers().getFirst("Idempotency-Key"));
        assertTrue(transport.requests().get(0).uri().getQuery().contains("visibility=ACTIVE"));
    }

    @Test
    void rejectsInvalidIdempotencyKeyWithoutTransportCall() {
        FakeClientHttpRequestFactory transport = new FakeClientHttpRequestFactory(request ->
                FakeClientHttpRequestFactory.Response.json(200, "{}"));

        var result = gateway(transport).expedite("user-1", "ORDER-001", "bad key", NOW);

        assertEquals("IDEMPOTENCY_KEY_INVALID", result.code());
        assertTrue(transport.requests().isEmpty());
    }

    @Test
    void mapsTransportFailureToTemporaryFailure() {
        FakeClientHttpRequestFactory transport = new FakeClientHttpRequestFactory(request -> {
            throw new IOException("simulated transport failure");
        });

        assertEquals(OrderLookupStatusEnum.TEMPORARY_FAILURE,
                gateway(transport).findOrder("ORDER-001", "user-1").status());
    }

    @Test
    void rejectsMalformedBaseUrlsBeforeTransport() {
        assertThrows(IllegalArgumentException.class, () -> new HttpOrderGateway(
                RestClient.builder(), "file:///tmp/orders", Duration.ofSeconds(1),
                new FakeClientHttpRequestFactory(request -> FakeClientHttpRequestFactory.Response.json(200, "{}"))));
    }

    private HttpOrderGateway gateway(FakeClientHttpRequestFactory transport) {
        return new HttpOrderGateway(RestClient.builder(), "http://orders.example.test", Duration.ofSeconds(1), transport);
    }
}
