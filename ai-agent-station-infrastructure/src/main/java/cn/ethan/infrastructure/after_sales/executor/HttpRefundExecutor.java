package cn.ethan.infrastructure.after_sales.executor;

import cn.ethan.core.after_sales.model.RefundCommandResultModel;
import cn.ethan.core.after_sales.model.RefundExecutionResultModel;
import cn.ethan.core.after_sales.port.RefundExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * HTTP 退款执行器：以退款单号作为外部幂等键，并将渠道异常归一为稳定失败码。
 *
 * @author ethan
 * @date 2026-08-12
 */
@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.refund-channel.mode", havingValue = "http")
public final class HttpRefundExecutor implements RefundExecutor {

    public static final String REJECTED = "REFUND_CHANNEL_REJECTED";
    public static final String TEMPORARY_FAILURE = "REFUND_CHANNEL_TEMPORARY_FAILURE";
    public static final String INVALID_RESPONSE = "REFUND_CHANNEL_INVALID_RESPONSE";

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRefundExecutor.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern FAILURE_CODE_PATTERN = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,63}");

    private final RestClient client;

    public HttpRefundExecutor(
            RestClient.Builder builder,
            @Value("${ai-agent.after-sales.refund-channel.base-url:http://127.0.0.1:18081}") String baseUrl,
            @Value("${ai-agent.after-sales.refund-channel.timeout:PT3S}") Duration timeout
    ) {
        Duration effectiveTimeout = normalizeTimeout(timeout);
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(effectiveTimeout).build()
        );
        requestFactory.setReadTimeout(effectiveTimeout);
        this.client = builder.clone()
                .baseUrl(requireBaseUrl(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public RefundExecutionResultModel execute(RefundCommandResultModel command) {
        try {
            RefundResponseDto response = client.post()
                    .uri("/refunds")
                    .header("Idempotency-Key", command.refundId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new RefundRequestDto(
                            command.refundId(), command.orderId(), command.amount(), command.currency()
                    ))
                    .retrieve()
                    .body(RefundResponseDto.class);
            return mapResponse(response);
        } catch (HttpClientErrorException rejected) {
            warn(command.refundId(), rejected);
            return RefundExecutionResultModel.failed(REJECTED);
        } catch (HttpServerErrorException | ResourceAccessException temporaryFailure) {
            warn(command.refundId(), temporaryFailure);
            return RefundExecutionResultModel.failed(TEMPORARY_FAILURE);
        } catch (RestClientException invalidResponse) {
            warn(command.refundId(), invalidResponse);
            return RefundExecutionResultModel.failed(INVALID_RESPONSE);
        }
    }

    private RefundExecutionResultModel mapResponse(RefundResponseDto response) {
        if (response == null || response.status() == null || response.status().isBlank()) {
            return RefundExecutionResultModel.failed(INVALID_RESPONSE);
        }
        String status = response.status().strip().toUpperCase(Locale.ROOT);
        if ("COMPLETED".equals(status)) {
            return RefundExecutionResultModel.succeeded();
        }
        if ("FAILED".equals(status) && response.failureCode() != null) {
            String failureCode = response.failureCode().strip();
            if (FAILURE_CODE_PATTERN.matcher(failureCode).matches()) {
                return RefundExecutionResultModel.failed(failureCode);
            }
        }
        return RefundExecutionResultModel.failed(INVALID_RESPONSE);
    }

    private void warn(String refundId, RuntimeException failure) {
        LOGGER.warn(
                "HTTP 退款渠道调用失败，refundId={}, exception={}",
                refundId, failure.getClass().getSimpleName()
        );
    }

    private static Duration normalizeTimeout(Duration timeout) {
        Duration effective = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (effective.isZero() || effective.isNegative() || effective.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("refund channel timeout must be between PT0S and PT30S");
        }
        return effective;
    }

    private static String requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("refund channel base URL is required");
        }
        try {
            URI value = new URI(baseUrl.strip());
            if (!value.isAbsolute()
                    || !("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                    || value.getHost() == null
                    || value.getUserInfo() != null
                    || value.getQuery() != null
                    || value.getFragment() != null) {
                throw new IllegalArgumentException("refund channel base URL must be an absolute HTTP(S) endpoint");
            }
            return value.toString();
        } catch (URISyntaxException invalid) {
            throw new IllegalArgumentException("refund channel base URL must be an absolute HTTP(S) endpoint", invalid);
        }
    }

    private record RefundRequestDto(
            String refundId,
            String orderId,
            BigDecimal amount,
            String currency
    ) {
    }

    private record RefundResponseDto(String status, String failureCode) {
    }
}
