package cn.ethan.ai.infrastructure.adapter.commerce;

import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.domain.agent.port.driven.IRefundGateway;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesLogisticsSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundHistorySnapshot;
import cn.ethan.ai.domain.agent.model.RefundGatewayResult;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.type.TypeReference;

@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.commerce-adapter", havingValue = "http")
public class HttpCommerceGateway implements IOrderGateway, IRefundGateway {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final AfterSalesJsonCodec jsonCodec;

    public HttpCommerceGateway(@Value("${ai-agent.after-sales.commerce-base-url}") String baseUrl,
                               @Value("${ai-agent.after-sales.commerce-timeout:3s}") Duration timeout) {
        this(baseUrl, timeout, AfterSalesJsonCodec.defaultCodec());
    }

    @Autowired
    public HttpCommerceGateway(@Value("${ai-agent.after-sales.commerce-base-url}") String baseUrl,
                               @Value("${ai-agent.after-sales.commerce-timeout:3s}") Duration timeout,
                               AfterSalesJsonCodec jsonCodec) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.requestTimeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/orders/" + encode(orderId)))
                .header("X-User-Id", requesterId)
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() == 403) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "__FOREIGN__", "ACCESS_DENIED", null));
        }
        requireSuccess(response, "query order");
        Map<String, Object> payload = payload(response.body(), "解析订单响应");
        return Optional.of(new AfterSalesOrderSnapshot(
                text(payload, "orderId"),
                requesterId,
                text(payload, "status"),
                integer(payload, "daysSinceDelivery")
        ));
    }

    @Override
    public Optional<AfterSalesLogisticsSnapshot> findLogistics(String orderId, String requesterId) {
        HttpResponse<String> response = send(readRequest("/orders/" + encode(orderId) + "/logistics", requesterId));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireReadable(response, "query logistics");
        Map<String, Object> payload = payload(response.body(), "解析物流响应");
        return Optional.of(new AfterSalesLogisticsSnapshot(
                text(payload, "orderId"),
                text(payload, "deliveryStatus"),
                parseDateTime(text(payload, "deliveredAt")),
                text(payload, "returnStatus")
        ));
    }

    @Override
    public Optional<AfterSalesRefundHistorySnapshot> findRefundHistory(String orderId, String requesterId) {
        HttpResponse<String> response = send(readRequest("/orders/" + encode(orderId) + "/refund-history", requesterId));
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireReadable(response, "query refund history");
        Map<String, Object> payload = payload(response.body(), "解析退款历史响应");
        return Optional.of(new AfterSalesRefundHistorySnapshot(
                text(payload, "orderId"),
                bool(payload, "activeRefund"),
                integerOrZero(payload, "completedRefundCount"),
                text(payload, "latestRefundStatus")
        ));
    }

    @Override
    public RefundGatewayResult executeRefund(String orderId, String userId, String idempotencyKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/refunds"))
                .header("Content-Type", "application/json")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(
                        jsonCodec.write(Map.of("orderId", orderId), "序列化退款请求")))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 409) {
            return new RefundGatewayResult(false, false, "ORDER_STATE_CONFLICT");
        }
        requireSuccess(response, "execute refund");
        Map<String, Object> payload = payload(response.body(), "解析退款响应");
        return new RefundGatewayResult(
                bool(payload, "success"),
                bool(payload, "idempotentReplay"),
                text(payload, "reason")
        );
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("commerce request interrupted", error);
        } catch (Exception error) {
            throw new IllegalStateException("commerce request failed", error);
        }
    }

    private HttpRequest readRequest(String path, String requesterId) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("X-User-Id", requesterId)
                .timeout(requestTimeout)
                .GET()
                .build();
    }

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(operation + " returned HTTP " + response.statusCode());
        }
    }

    private void requireReadable(HttpResponse<String> response, String operation) {
        if (response.statusCode() == 403) {
            throw new IllegalStateException(operation + " access denied");
        }
        requireSuccess(response, operation);
    }

    private LocalDateTime parseDateTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Map<String, Object> payload(String value, String operation) {
        return jsonCodec.read(value, new TypeReference<>() {
        }, operation);
    }

    private String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        return value instanceof Number number ? number.intValue() : null;
    }

    private boolean bool(Map<String, Object> payload, String field) {
        return Boolean.TRUE.equals(payload.get(field));
    }

    private int integerOrZero(Map<String, Object> payload, String field) {
        Integer value = integer(payload, field);
        return value == null ? 0 : value;
    }
}
