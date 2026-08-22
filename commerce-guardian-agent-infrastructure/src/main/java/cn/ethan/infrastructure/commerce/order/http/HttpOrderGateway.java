package cn.ethan.infrastructure.commerce.order.http;

import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderActionGateway;
import cn.ethan.core.commerce.order.OrderSnapshotModel;
import cn.ethan.core.commerce.order.OrderStatusEnum;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderSearchCriteria;
import cn.ethan.core.commerce.order.OrderSearchResultModel;
import cn.ethan.core.commerce.order.OrderSearchStatusEnum;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * HTTP 订单网关：用于订单数据由外部服务管理的部署场景。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Component
@ConditionalOnProperty(name = "ai-agent.order.gateway", havingValue = "http")
public final class HttpOrderGateway implements OrderGateway, OrderActionGateway {

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
                    response.logisticsStatus(),
                    response.paidAmount(),
                    response.currency(),
                    response.itemSummary(),
                    response.hiddenAt()
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

    @Override
    public OrderSearchResultModel searchOrders(OrderSearchCriteria criteria, String userId) {
        if (criteria == null || userId == null || userId.isBlank()) {
            return OrderSearchResultModel.success(List.of());
        }
        try {
            List<HttpOrderResponseDto> response = client.get()
                    .uri(uri -> {
                        var builder = uri.path("/orders/search");
                        if (criteria.createdFrom() != null) {
                            builder.queryParam("createdFrom", criteria.createdFrom());
                        }
                        if (criteria.createdTo() != null) {
                            builder.queryParam("createdTo", criteria.createdTo());
                        }
                        if (criteria.minAmount() != null) {
                            builder.queryParam("minAmount", criteria.minAmount());
                        }
                        if (criteria.maxAmount() != null) {
                            builder.queryParam("maxAmount", criteria.maxAmount());
                        }
                        if (!criteria.statuses().isEmpty()) {
                            builder.queryParam("status", String.join(",",
                                    criteria.statusList().stream().map(Enum::name).toList()));
                        }
                        if (criteria.keyword() != null) {
                            builder.queryParam("keyword", criteria.keyword());
                        }
                        if (criteria.logisticsStalledDays() != null) {
                            builder.queryParam("logisticsStalledDays", criteria.logisticsStalledDays());
                        }
                        builder.queryParam("visibility", criteria.visibility().name());
                        builder.queryParam("limit", criteria.limit());
                        return builder.build();
                    })
                    .header("X-User-Id", userId.strip())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<HttpOrderResponseDto>>() { });
            if (response == null) {
                return OrderSearchResultModel.success(List.of());
            }
            List<OrderSnapshotModel> orders = response.stream()
                    .filter(Objects::nonNull)
                    .filter(order -> userId.equals(order.userId()))
                    .filter(order -> order.orderId() != null && order.status() != null
                            && !order.status().isBlank())
                    .map(this::toSnapshot)
                    .limit(criteria.limit())
                    .toList();
            return new OrderSearchResultModel(OrderSearchStatusEnum.SUCCESS, orders);
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden unavailable) {
            return OrderSearchResultModel.success(List.of());
        } catch (RuntimeException temporaryFailure) {
            LOGGER.warn("HTTP 订单搜索降级为临时失败，exception={}",
                    temporaryFailure.getClass().getSimpleName());
            return OrderSearchResultModel.temporaryFailure();
        }
    }

    @Override
    public OrderActionResult refund(String userId, String orderId, String reason, Instant now) {
        if (userId == null || userId.isBlank() || orderId == null || orderId.isBlank()
                || reason == null || reason.isBlank()) {
            return OrderActionResult.failed(false, "REFUND_ARGUMENT_INVALID", "退款参数不完整");
        }
        try {
            HttpOrderActionResponse response = client.post()
                    .uri(uri -> uri.path("/orders/{id}/refund").build(orderId.strip()))
                    .header("X-User-Id", userId.strip())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("reason", reason.strip()))
                    .retrieve()
                    .body(HttpOrderActionResponse.class);
            if (response == null) {
                return OrderActionResult.failed(true, "ORDER_ACTION_EMPTY_RESPONSE", "订单服务未返回退款结果");
            }
            return new OrderActionResult(Boolean.TRUE.equals(response.success()),
                    Boolean.TRUE.equals(response.retryable()), response.code(), response.message());
        } catch (HttpClientErrorException.NotFound notFound) {
            return OrderActionResult.failed(false, "ORDER_NOT_FOUND", "订单不存在");
        } catch (HttpClientErrorException.Forbidden forbidden) {
            return OrderActionResult.failed(false, "ORDER_NOT_OWNED", "订单不属于当前用户");
        } catch (RuntimeException temporaryFailure) {
            LOGGER.warn("HTTP 退款调用暂时失败，exception={}", temporaryFailure.getClass().getSimpleName());
            return OrderActionResult.failed(true, "ORDER_ACTION_TEMPORARY_FAILURE", "订单服务暂时不可用");
        }
    }

    private OrderSnapshotModel toSnapshot(HttpOrderResponseDto response) {
        return new OrderSnapshotModel(
                response.orderId(), response.userId(), OrderStatusEnum.fromValue(response.status()),
                response.daysSinceDelivery(), response.createdAt(), response.expectedDeliveryAt(),
                response.lastLogisticsAt(), response.logisticsStatus(), response.paidAmount(),
                response.currency(), response.itemSummary(), response.hiddenAt());
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
            String logisticsStatus,
            java.math.BigDecimal paidAmount,
            String currency,
            String itemSummary,
            Instant hiddenAt
    ) {
    }

    private record HttpOrderActionResponse(
            Boolean success,
            Boolean retryable,
            String code,
            String message
    ) {
    }

}
