package cn.ethan.ai.infrastructure.adapter.commerce;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class HttpCommerceGatewayTest {

    private final Map<String, StubResponse> responses = new ConcurrentHashMap<>();
    private final AtomicInteger refundRequests = new AtomicInteger();
    private final AtomicReference<CapturedRequest> lastRefundRequest = new AtomicReference<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void shouldMapSuccessfulCommerceResponsesAndPreserveRefundHeaders() {
        respond("GET /orders/ORDER-1", 200,
                "{\"orderId\":\"ORDER-1\",\"status\":\"PAID\",\"daysSinceDelivery\":3}");
        respond("GET /orders/ORDER-1/logistics", 200,
                "{\"orderId\":\"ORDER-1\",\"deliveryStatus\":\"DELIVERED\","
                        + "\"deliveredAt\":\"2026-07-01T10:15:00\",\"returnStatus\":\"NONE\"}");
        respond("GET /orders/ORDER-1/refund-history", 200,
                "{\"orderId\":\"ORDER-1\",\"activeRefund\":false,"
                        + "\"completedRefundCount\":1,\"latestRefundStatus\":\"COMPLETED\"}");
        respond("POST /refunds", 200,
                "{\"success\":true,\"idempotentReplay\":false,\"reason\":\"REFUND_EXECUTED\"}");
        HttpCommerceGateway gateway = gateway(Duration.ofSeconds(1));

        var order = gateway.findOrder("ORDER-1", "user-1").orElseThrow();
        var logistics = gateway.findLogistics("ORDER-1", "user-1").orElseThrow();
        var history = gateway.findRefundHistory("ORDER-1", "user-1").orElseThrow();
        var refund = gateway.executeRefund("ORDER-1", "user-1", "case-1:REFUND");

        Assertions.assertEquals("PAID", order.status());
        Assertions.assertEquals(3, order.daysSinceDelivery());
        Assertions.assertEquals(LocalDateTime.of(2026, 7, 1, 10, 15), logistics.deliveredAt());
        Assertions.assertEquals(1, history.completedRefundCount());
        Assertions.assertTrue(refund.success());
        Assertions.assertEquals(1, refundRequests.get());
        Assertions.assertEquals("user-1", lastRefundRequest.get().userId());
        Assertions.assertEquals("case-1:REFUND", lastRefundRequest.get().idempotencyKey());
        Assertions.assertEquals("{\"orderId\":\"ORDER-1\"}", lastRefundRequest.get().body());
    }

    @Test
    void shouldPreserveNotFoundAndAccessDeniedSemantics() {
        respond("GET /orders/MISSING", 404, "");
        respond("GET /orders/FOREIGN", 403, "");
        respond("GET /orders/FOREIGN/logistics", 403, "");
        HttpCommerceGateway gateway = gateway(Duration.ofSeconds(1));

        Assertions.assertTrue(gateway.findOrder("MISSING", "user-1").isEmpty());
        Assertions.assertEquals("__FOREIGN__", gateway.findOrder("FOREIGN", "user-1").orElseThrow().ownerId());
        IllegalStateException denied = Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.findLogistics("FOREIGN", "user-1"));
        Assertions.assertTrue(denied.getMessage().contains("access denied"));
    }

    @Test
    void shouldMapRefundConflictAndNeverRetryServerFailure() {
        respond("POST /refunds", 409, "");
        HttpCommerceGateway gateway = gateway(Duration.ofSeconds(1));
        Assertions.assertEquals("ORDER_STATE_CONFLICT",
                gateway.executeRefund("ORDER-1", "user-1", "case-1:REFUND").reason());
        Assertions.assertEquals(1, refundRequests.get());

        refundRequests.set(0);
        respond("POST /refunds", 500, "{\"error\":\"failed\"}");
        IllegalStateException failure = Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.executeRefund("ORDER-1", "user-1", "case-1:REFUND"));
        Assertions.assertTrue(failure.getMessage().contains("HTTP 500"));
        Assertions.assertEquals(1, refundRequests.get());
    }

    @Test
    void shouldDistinguishInvalidResponseAndTimeout() {
        respond("GET /orders/BROKEN", 200, "not-json");
        HttpCommerceGateway gateway = gateway(Duration.ofSeconds(1));
        IllegalStateException invalid = Assertions.assertThrows(IllegalStateException.class,
                () -> gateway.findOrder("BROKEN", "user-1"));
        Assertions.assertTrue(invalid.getMessage().contains("response invalid"));

        responses.put("GET /orders/SLOW", new StubResponse(200,
                "{\"orderId\":\"SLOW\",\"status\":\"PAID\"}", 250));
        HttpCommerceGateway shortTimeoutGateway = gateway(Duration.ofMillis(50));
        IllegalStateException timeout = Assertions.assertThrows(IllegalStateException.class,
                () -> shortTimeoutGateway.findOrder("SLOW", "user-1"));
        Assertions.assertTrue(timeout.getMessage().contains("timed out"));
    }

    private HttpCommerceGateway gateway(Duration timeout) {
        return new HttpCommerceGateway(baseUrl, timeout, RestClient.builder());
    }

    private void respond(String request, int status, String body) {
        responses.put(request, new StubResponse(status, body, 0));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        if ("POST /refunds".equals(key)) {
            refundRequests.incrementAndGet();
            lastRefundRequest.set(new CapturedRequest(
                    exchange.getRequestHeaders().getFirst("X-User-Id"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        }
        StubResponse response = responses.getOrDefault(key, new StubResponse(404, "", 0));
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record StubResponse(int status, String body, long delayMillis) {
    }

    private record CapturedRequest(String userId, String idempotencyKey, String body) {
    }
}
