package cn.ethan.infrastructure.after_sales.executor;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundExecutionResultModel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 退款执行器测试：验证渠道契约、幂等键、超时和稳定失败码映射。
 *
 * @author ethan
 * @date 2026-08-12
 */
class HttpRefundExecutorTest {

    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    private HttpServer server;
    private int responseStatus;
    private String responseBody;
    private long responseDelayMillis;

    @BeforeEach
    void startServer() throws IOException {
        responseStatus = 200;
        responseBody = "{\"status\":\"COMPLETED\"}";
        responseDelayMillis = 0;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/refunds", this::respond);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void completesAndSendsMinimalIdempotentContract() {
        RefundExecutionResultModel result = executor(Duration.ofSeconds(1)).execute(command());

        assertTrue(result.completed());
        assertEquals("REFUND-001", idempotencyKey.get());
        assertTrue(requestBody.get().contains("\"refundId\":\"REFUND-001\""));
        assertTrue(requestBody.get().contains("\"orderId\":\"ORDER-001\""));
        assertTrue(requestBody.get().contains("\"amount\":99.00"));
        assertTrue(requestBody.get().contains("\"currency\":\"CNY\""));
        assertFalse(requestBody.get().contains("user-1"));
    }

    @Test
    void preservesStableBusinessFailureCode() {
        responseBody = "{\"status\":\"FAILED\",\"failureCode\":\"BALANCE_UNAVAILABLE\"}";

        RefundExecutionResultModel result = executor(Duration.ofSeconds(1)).execute(command());

        assertFalse(result.completed());
        assertEquals("BALANCE_UNAVAILABLE", result.failureCode());
    }

    @Test
    void mapsClientAndServerErrors() {
        responseStatus = 422;
        RefundExecutionResultModel rejected = executor(Duration.ofSeconds(1)).execute(command());
        assertEquals(HttpRefundExecutor.REJECTED, rejected.failureCode());

        responseStatus = 503;
        RefundExecutionResultModel temporary = executor(Duration.ofSeconds(1)).execute(command());
        assertEquals(HttpRefundExecutor.TEMPORARY_FAILURE, temporary.failureCode());
    }

    @Test
    void mapsTimeoutToTemporaryFailure() {
        responseDelayMillis = 200;

        RefundExecutionResultModel result = executor(Duration.ofMillis(30)).execute(command());

        assertEquals(HttpRefundExecutor.TEMPORARY_FAILURE, result.failureCode());
    }

    @Test
    void mapsEmptyAndMalformedResponsesToInvalidResponse() {
        responseBody = "{}";
        RefundExecutionResultModel empty = executor(Duration.ofSeconds(1)).execute(command());
        assertEquals(HttpRefundExecutor.INVALID_RESPONSE, empty.failureCode());

        responseBody = "not-json";
        RefundExecutionResultModel malformed = executor(Duration.ofSeconds(1)).execute(command());
        assertEquals(HttpRefundExecutor.INVALID_RESPONSE, malformed.failureCode());
    }

    @Test
    void rejectsOutOfRangeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> executor(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> executor(Duration.ofSeconds(31)));
    }

    private HttpRefundExecutor executor(Duration timeout) {
        return new HttpRefundExecutor(
                RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                timeout
        );
    }

    private RefundCommandResultModel command() {
        Instant now = Instant.parse("2026-08-12T12:00:00Z");
        return new RefundCommandResultModel(
                "REFUND-001", "CASE-001", "RUN-001", "ORDER-001", "user-1", "PROCESSING",
                new BigDecimal("99.00"), "CNY", "", 1, now, now.plusSeconds(30), "", 1,
                now, now
        );
    }

    private void respond(HttpExchange exchange) throws IOException {
        try (exchange) {
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
