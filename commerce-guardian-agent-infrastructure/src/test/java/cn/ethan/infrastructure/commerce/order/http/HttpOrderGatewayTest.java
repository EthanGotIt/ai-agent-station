package cn.ethan.infrastructure.commerce.order.http;

import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderVisibilityEnum;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 订单网关测试：验证外部响应归属、超时和成功映射边界。
 *
 * @author ethan
 * @date 2026-08-05
 */
class HttpOrderGatewayTest {

    private final AtomicReference<String> requestUserId = new AtomicReference<>();
    private final AtomicReference<String> requestUri = new AtomicReference<>();

    private HttpServer server;
    private String responseBody;
    private boolean holdResponse;
    private CountDownLatch responseRelease;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = "{}";
        holdResponse = false;
        responseRelease = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        responseRelease.countDown();
        server.stop(0);
    }

    @Test
    void returnsFoundOrderForMatchingUser() {
        responseBody = """
                {
                  "accessDenied": false,
                  "orderId": "ORDER-001",
                  "userId": "user-1",
                  "status": "PAID",
                  "daysSinceDelivery": null
                }
                """;

        OrderLookupResultModel result = gateway(Duration.ofSeconds(1))
                .findOrder("ORDER-001", "user-1");

        assertEquals(OrderLookupStatusEnum.FOUND, result.status());
        assertEquals("user-1", result.order().userId());
        assertEquals("user-1", requestUserId.get());
    }

    @Test
    void rejectsResponseOwnedByAnotherUser() {
        responseBody = """
                {
                  "accessDenied": false,
                  "orderId": "ORDER-001",
                  "userId": "user-2",
                  "status": "PAID"
                }
                """;

        OrderLookupResultModel result = gateway(Duration.ofSeconds(1))
                .findOrder("ORDER-001", "user-1");

        assertEquals(OrderLookupStatusEnum.ACCESS_DENIED, result.status());
    }

    @Test
    void timeoutBecomesTemporaryFailure() {
        holdResponse = true;
        responseBody = """
                {
                  "orderId": "ORDER-001",
                  "userId": "user-1",
                  "status": "PAID"
                }
                """;

        OrderLookupResultModel result = gateway(Duration.ofMillis(50))
                .findOrder("ORDER-001", "user-1");

        assertEquals(OrderLookupStatusEnum.TEMPORARY_FAILURE, result.status());
    }

    @Test
    void searchesWithStructuredFiltersAndProjectsOwnedOrders() {
        responseBody = """
                [{
                  "orderId": "ORDER-001",
                  "userId": "user-1",
                  "status": "PAID",
                  "createdAt": "2026-08-22T08:00:00Z",
                  "paidAmount": 99.00,
                  "currency": "CNY",
                  "itemSummary": "无线耳机",
                  "logisticsStatus": "待发货"
                }]
                """;

        OrderSearchCriteria criteria = new OrderSearchCriteria(
                Instant.parse("2026-08-22T00:00:00Z"), Instant.parse("2026-08-22T23:59:59Z"),
                new BigDecimal("50"), new BigDecimal("120"), Set.of(OrderStatusEnum.PAID),
                "耳机", 3, OrderVisibilityEnum.ACTIVE, 5);
        var result = gateway(Duration.ofSeconds(1)).searchOrders(criteria, "user-1");

        assertEquals(OrderSearchStatusEnum.SUCCESS, result.status());
        assertEquals(1, result.orders().size());
        assertEquals("无线耳机", result.orders().get(0).itemSummary());
        assertEquals("user-1", requestUserId.get());
        assertTrue(requestUri.get().contains("createdFrom=2026-08-22T00:00:00Z"));
        assertTrue(requestUri.get().contains("minAmount=50"));
        assertTrue(requestUri.get().contains("visibility=ACTIVE"));
    }

    @Test
    void rejectsMalformedBaseUrls() {
        assertThrows(IllegalArgumentException.class, () -> new HttpOrderGateway(
                RestClient.builder(), "orders.example.test", Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HttpOrderGateway(
                RestClient.builder(), "https://user:secret@orders.example.test", Duration.ofSeconds(1)
        ));
        assertThrows(IllegalArgumentException.class, () -> new HttpOrderGateway(
                RestClient.builder(), "https://orders.example.test#fragment", Duration.ofSeconds(1)
        ));
    }

    private HttpOrderGateway gateway(Duration timeout) {
        return new HttpOrderGateway(
                RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                timeout
        );
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            requestUserId.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
            requestUri.set(exchange.getRequestURI().toString());
            if (holdResponse) {
                try {
                    responseRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
