package cn.ethan.infrastructure.commerce.order.http;

import cn.ethan.core.commerce.order.LogisticsEventModel;
import cn.ethan.core.commerce.order.LogisticsGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
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
import java.util.List;

/**
 * HTTP 物流网关：约束外部订单服务的物流时间线接口，失败时不泄露外部细节。
 *
 * @author ethan
 * @date 2026-08-10
 */
@Component
@ConditionalOnProperty(name = "ai-agent.order.gateway", havingValue = "http")
public final class HttpLogisticsGateway implements LogisticsGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpLogisticsGateway.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final RestClient client;

    public HttpLogisticsGateway(
            RestClient.Builder builder,
            @Value("${ai-agent.order.base-url:http://localhost:18080}") String baseUrl,
            @Value("${ai-agent.order.http-timeout:PT5S}") Duration timeout
    ) {
        this(builder, baseUrl, timeout, defaultRequestFactory(normalizeTimeout(timeout)));
    }

    /** 注入 HTTP Transport 以隔离协议单测；生产装配仍使用带连接/读取超时的 JDK 客户端。 */
    public HttpLogisticsGateway(
            RestClient.Builder builder,
            String baseUrl,
            Duration timeout,
            ClientHttpRequestFactory requestFactory
    ) {
        normalizeTimeout(timeout);
        if (requestFactory == null) {
            throw new IllegalArgumentException("logistics HTTP request factory is required");
        }
        this.client = builder.clone().baseUrl(requireBaseUrl(baseUrl)).requestFactory(requestFactory).build();
    }

    private static ClientHttpRequestFactory defaultRequestFactory(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        return requestFactory;
    }

    @Override
    public List<LogisticsEventModel> findTrace(String orderId, String userId) {
        if (orderId == null || orderId.isBlank() || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            List<HttpLogisticsEventDto> response = client.get()
                    .uri(uri -> uri.path("/orders/{id}/logistics").build(orderId))
                    .header("X-User-Id", userId)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return response == null ? List.of() : response.stream()
                    .filter(event -> event.eventId() != null && event.status() != null
                            && event.description() != null && event.occurredAt() != null)
                    .map(event -> new LogisticsEventModel(
                            event.eventId(), orderId, event.status(), event.location(), event.description(),
                            event.occurredAt()
                    ))
                    .sorted(java.util.Comparator.comparing(LogisticsEventModel::occurredAt))
                    .toList();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden unavailable) {
            return List.of();
        } catch (RuntimeException temporaryFailure) {
            LOGGER.warn("HTTP 物流查询降级为空列表，exception={}",
                    temporaryFailure.getClass().getSimpleName());
            return List.of();
        }
    }

    private static Duration normalizeTimeout(Duration timeout) {
        if (timeout == null) {
            return DEFAULT_TIMEOUT;
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("logistics HTTP timeout must be between PT0S and PT30S");
        }
        return timeout;
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("logistics base URL is required");
        }
        try {
            URI value = new URI(baseUrl.strip());
            if (!value.isAbsolute()
                    || !("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                    || value.getHost() == null
                    || value.getUserInfo() != null
                    || value.getQuery() != null
                    || value.getFragment() != null) {
                throw new IllegalArgumentException("logistics base URL must be an absolute HTTP(S) endpoint");
            }
            return value.toString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("logistics base URL must be an absolute HTTP(S) endpoint", invalid);
        }
    }

    private record HttpLogisticsEventDto(
            String eventId,
            String status,
            String location,
            String description,
            Instant occurredAt
    ) {
    }
}
