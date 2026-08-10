package cn.ethan.infrastructure.order.gateway;

import cn.ethan.core.order.model.OrderLookupResultModel;
import cn.ethan.core.order.model.OrderSnapshotModel;
import cn.ethan.core.order.model.OrderItemModel;
import cn.ethan.core.order.model.RecentOrderModel;
import cn.ethan.core.order.enums.OrderStatusEnum;
import cn.ethan.core.order.port.OrderGateway;
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
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

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
                .baseUrl(baseUrl)
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

    @Override
    public List<RecentOrderModel> listRecentOrders(String userId, int limit) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), 10);
        try {
            List<HttpRecentOrderDto> response = client.get()
                    .uri(uri -> uri.path("/orders").queryParam("limit", effectiveLimit).build())
                    .header("X-User-Id", userId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return response == null ? List.of() : response.stream()
                    .filter(order -> order.orderId() != null && order.status() != null)
                    .map(order -> new RecentOrderModel(
                            order.orderId(), OrderStatusEnum.fromValue(order.status()), order.createdAt()
                    ))
                    .toList();
        } catch (RuntimeException temporaryFailure) {
            LOGGER.warn("HTTP 近期订单查询降级为空列表，exception={}",
                    temporaryFailure.getClass().getSimpleName());
            return List.of();
        }
    }

    @Override
    public List<OrderItemModel> findItems(String orderId, String userId) {
        if (findOrder(orderId, userId).status() != cn.ethan.core.order.enums.OrderLookupStatusEnum.FOUND) {
            return List.of();
        }
        try {
            List<HttpOrderItemDto> response = client.get()
                    .uri(uri -> uri.path("/orders/{id}/items").build(orderId))
                    .header("X-User-Id", userId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return response == null ? List.of() : response.stream()
                    .filter(item -> item.itemId() != null && item.productName() != null
                            && item.quantity() != null && item.unitPrice() != null)
                    .map(item -> new OrderItemModel(
                            item.itemId(), orderId, item.productName(), item.quantity(), item.unitPrice()
                    ))
                    .toList();
        } catch (RuntimeException temporaryFailure) {
            LOGGER.warn("HTTP 订单商品查询降级为空列表，exception={}",
                    temporaryFailure.getClass().getSimpleName());
            return List.of();
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

    private record HttpRecentOrderDto(String orderId, String status, Instant createdAt) {
    }

    private record HttpOrderItemDto(String itemId, String productName, Integer quantity, BigDecimal unitPrice) {
    }
}
