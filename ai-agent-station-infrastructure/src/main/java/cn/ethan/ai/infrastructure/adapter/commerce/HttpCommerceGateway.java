package cn.ethan.ai.infrastructure.adapter.commerce;

import cn.ethan.ai.domain.agent.port.driven.IOrderGateway;
import cn.ethan.ai.domain.agent.port.driven.IRefundGateway;
import cn.ethan.ai.domain.agent.model.AfterSalesOrderSnapshot;
import cn.ethan.ai.domain.agent.model.RefundGatewayResult;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.commerce-adapter", havingValue = "http")
public class HttpCommerceGateway implements IOrderGateway, IRefundGateway {

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration requestTimeout;

    public HttpCommerceGateway(@Value("${ai-agent.after-sales.commerce-base-url}") String baseUrl,
                               @Value("${ai-agent.after-sales.commerce-timeout:3s}") Duration timeout) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.requestTimeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
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
        JSONObject payload = JSON.parseObject(response.body());
        return Optional.of(new AfterSalesOrderSnapshot(
                payload.getString("orderId"),
                requesterId,
                payload.getString("status"),
                payload.getInteger("daysSinceDelivery")
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
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("orderId", orderId))))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 409) {
            return new RefundGatewayResult(false, false, "ORDER_STATE_CONFLICT");
        }
        requireSuccess(response, "execute refund");
        JSONObject payload = JSON.parseObject(response.body());
        return new RefundGatewayResult(
                payload.getBooleanValue("success"),
                payload.getBooleanValue("idempotentReplay"),
                payload.getString("reason")
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

    private void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(operation + " returned HTTP " + response.statusCode());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
