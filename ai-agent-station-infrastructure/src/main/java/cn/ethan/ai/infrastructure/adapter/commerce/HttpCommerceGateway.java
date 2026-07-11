package cn.ethan.ai.infrastructure.adapter.commerce;

import cn.ethan.ai.domain.agent.model.AfterSalesLogisticsSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesRefundHistorySnapshot;
import cn.ethan.ai.domain.agent.model.RefundGatewayResult;
import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.domain.agent.port.driven.IRefundGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.commerce-adapter", havingValue = "http")
public class HttpCommerceGateway implements IOrderGateway, IRefundGateway {

    private final RestClient restClient;

    @Autowired
    public HttpCommerceGateway(@Value("${ai-agent.after-sales.commerce-base-url}") String baseUrl,
                               @Value("${ai-agent.after-sales.commerce-timeout:3s}") Duration timeout,
                               RestClient.Builder restClientBuilder) {
        this(createClient(baseUrl, timeout, restClientBuilder));
    }

    HttpCommerceGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Optional<AfterSalesOrderSnapshot> findOrder(String orderId, String requesterId) {
        ResponseEntity<OrderResponse> response = get("/orders/{orderId}", orderId, requesterId, OrderResponse.class);
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        if (response.getStatusCode().value() == 403) {
            return Optional.of(new AfterSalesOrderSnapshot(orderId, "__FOREIGN__", "ACCESS_DENIED", null));
        }
        requireSuccess(response.getStatusCode(), "query order");
        OrderResponse payload = requireBody(response, "query order");
        return Optional.of(new AfterSalesOrderSnapshot(
                payload.orderId(), requesterId, payload.status(), payload.daysSinceDelivery()));
    }

    @Override
    public Optional<AfterSalesLogisticsSnapshot> findLogistics(String orderId, String requesterId) {
        ResponseEntity<LogisticsResponse> response = get(
                "/orders/{orderId}/logistics", orderId, requesterId, LogisticsResponse.class);
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        requireReadable(response.getStatusCode(), "query logistics");
        LogisticsResponse payload = requireBody(response, "query logistics");
        return Optional.of(new AfterSalesLogisticsSnapshot(
                payload.orderId(), payload.deliveryStatus(), parseDateTime(payload.deliveredAt()),
                payload.returnStatus()));
    }

    @Override
    public Optional<AfterSalesRefundHistorySnapshot> findRefundHistory(String orderId, String requesterId) {
        ResponseEntity<RefundHistoryResponse> response = get(
                "/orders/{orderId}/refund-history", orderId, requesterId, RefundHistoryResponse.class);
        if (response.getStatusCode().value() == 404) {
            return Optional.empty();
        }
        requireReadable(response.getStatusCode(), "query refund history");
        RefundHistoryResponse payload = requireBody(response, "query refund history");
        return Optional.of(new AfterSalesRefundHistorySnapshot(
                payload.orderId(), Boolean.TRUE.equals(payload.activeRefund()),
                payload.completedRefundCount() == null ? 0 : payload.completedRefundCount(),
                payload.latestRefundStatus()));
    }

    @Override
    public RefundGatewayResult executeRefund(String orderId, String userId, String idempotencyKey) {
        ResponseEntity<RefundResponse> response = exchange(() -> restClient.post()
                .uri("/refunds")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .body(new RefundRequest(orderId))
                .retrieve()
                .onStatus(status -> true, (request, ignored) -> {
                })
                .toEntity(RefundResponse.class));
        if (response.getStatusCode().value() == 409) {
            return new RefundGatewayResult(false, false, "ORDER_STATE_CONFLICT");
        }
        requireSuccess(response.getStatusCode(), "execute refund");
        RefundResponse payload = requireBody(response, "execute refund");
        return new RefundGatewayResult(Boolean.TRUE.equals(payload.success()),
                Boolean.TRUE.equals(payload.idempotentReplay()), payload.reason());
    }

    private <T> ResponseEntity<T> get(String path, String orderId, String requesterId, Class<T> responseType) {
        return exchange(() -> restClient.get()
                .uri(path, orderId)
                .header("X-User-Id", requesterId)
                .retrieve()
                .onStatus(status -> true, (request, ignored) -> {
                })
                .toEntity(responseType));
    }

    private <T> T exchange(HttpExchange<T> exchange) {
        try {
            return exchange.execute();
        } catch (ResourceAccessException error) {
            if (hasCause(error, InterruptedException.class)) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("commerce request interrupted", error);
            }
            if (hasCause(error, HttpTimeoutException.class)) {
                throw new IllegalStateException("commerce request timed out", error);
            }
            throw new IllegalStateException("commerce request failed", error);
        } catch (RestClientException error) {
            throw new IllegalStateException("commerce response invalid", error);
        }
    }

    private void requireSuccess(HttpStatusCode status, String operation) {
        if (!status.is2xxSuccessful()) {
            throw new IllegalStateException(operation + " returned HTTP " + status.value());
        }
    }

    private void requireReadable(HttpStatusCode status, String operation) {
        if (status.value() == 403) {
            throw new IllegalStateException(operation + " access denied");
        }
        requireSuccess(status, operation);
    }

    private <T> T requireBody(ResponseEntity<T> response, String operation) {
        if (response.getBody() == null) {
            throw new IllegalStateException(operation + " returned an empty body");
        }
        return response.getBody();
    }

    private LocalDateTime parseDateTime(String value) {
        return value == null || value.isBlank() ? null : LocalDateTime.parse(value);
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static RestClient createClient(String baseUrl, Duration timeout, RestClient.Builder builder) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.clone()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .build();
    }

    @FunctionalInterface
    private interface HttpExchange<T> {
        T execute();
    }

    private record OrderResponse(String orderId, String status, Integer daysSinceDelivery) {
    }

    private record LogisticsResponse(String orderId,
                                     String deliveryStatus,
                                     String deliveredAt,
                                     String returnStatus) {
    }

    private record RefundHistoryResponse(String orderId,
                                         Boolean activeRefund,
                                         Integer completedRefundCount,
                                         String latestRefundStatus) {
    }

    private record RefundRequest(String orderId) {
    }

    private record RefundResponse(Boolean success, Boolean idempotentReplay, String reason) {
    }
}
