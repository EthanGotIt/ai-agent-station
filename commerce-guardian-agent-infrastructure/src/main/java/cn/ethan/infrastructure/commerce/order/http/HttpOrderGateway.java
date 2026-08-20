package cn.ethan.infrastructure.commerce.order.http;

import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;

/**
 * HTTP 订单网关：用于订单数据由外部服务管理的部署场景。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Component
@ConditionalOnProperty(name = "ai-agent.order.gateway", havingValue = "http")
public final class HttpOrderGateway implements OrderGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpOrderGateway.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;

    public HttpOrderGateway(
            RestClient.Builder builder,
            @Value("${ai-agent.order.base-url:http://localhost:18080}") String baseUrl,
            @Value("${ai-agent.order.http-timeout:PT5S}") Duration timeout
    ) {
        Duration effectiveTimeout = normalizeTimeout(timeout);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(effectiveTimeout)
                        .build()
        );
        requestFactory.setReadTimeout(effectiveTimeout);
        this.client = builder.clone()
                .baseUrl(requireBaseUrl(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public OrderLookupResultModel findOrder(String orderId, String userId) {
        if (orderId == null || orderId.isBlank() || userId == null || userId.isBlank()) {
            return OrderLookupResultModel.notFound();
        }

        try {
            HttpOrderResponseDto response = client.get()
                    .uri(uri -> uri.path("/orders/{id}").build(orderId))
                    .header("X-User-Id", userId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(HttpOrderResponseDto.class);
            if (response == null) {
                return OrderLookupResultModel.notFound();
            }
            if (Boolean.TRUE.equals(response.accessDenied())) {
                return OrderLookupResultModel.denied();
            }
            if (response.userId() == null || !userId.equals(response.userId())) {
                return OrderLookupResultModel.denied();
            }
            if (!orderId.equalsIgnoreCase(response.orderId())
                    || response.status() == null
                    || response.status().isBlank()) {
                return OrderLookupResultModel.temporaryFailure();
            }
            return OrderLookupResultModel.found(new OrderSnapshotModel(
                    response.orderId(),
                    response.userId(),
                    OrderStatusEnum.fromValue(response.status()),
                    response.daysSinceDelivery(),
                    response.createdAt(),
                    response.expectedDeliveryAt(),
                    response.lastLogisticsAt(),
                    response.logisticsStatus()
            ));
        } catch (HttpClientErrorException.NotFound notFound) {
            return OrderLookupResultModel.notFound();
        } catch (HttpClientErrorException.Forbidden forbidden) {
            return OrderLookupResultModel.denied();
        } catch (RuntimeException temporaryFailure) {
            LOGGER.warn(
                    "HTTP 订单查询降级为临时失败，exception={}",
                    temporaryFailure.getClass().getSimpleName()
            );
            return OrderLookupResultModel.temporaryFailure();
        }
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT;
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("order HTTP timeout must be between PT0S and PT30S");
        }
        return timeout;
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("order base URL is required");
        }
        try {
            URI value = new URI(baseUrl.strip());
            if (!value.isAbsolute()
                    || !("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                    || value.getHost() == null
                    || value.getUserInfo() != null
                    || value.getQuery() != null
                    || value.getFragment() != null) {
                throw new IllegalArgumentException("order base URL must be an absolute HTTP(S) endpoint");
            }
            return value.toString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("order base URL must be an absolute HTTP(S) endpoint", invalid);
        }
    }

    private record HttpOrderResponseDto(
            Boolean accessDenied,
            String orderId,
            String userId,
            String status,
            Integer daysSinceDelivery,
            Instant createdAt,
            Instant expectedDeliveryAt,
            Instant lastLogisticsAt,
            String logisticsStatus
    ) {
    }

}
