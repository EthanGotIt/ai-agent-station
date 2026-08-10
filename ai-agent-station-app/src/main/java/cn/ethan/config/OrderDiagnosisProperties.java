package cn.ethan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 订单诊断配置：约束发货延迟与物流停滞的确定性判定阈值。
 *
 * @author ethan
 * @date 2026-08-06
 */
@ConfigurationProperties(prefix = "ai-agent.order.diagnosis")
public record OrderDiagnosisProperties(
        Duration shipmentDelayThreshold,
        Duration logisticsStallThreshold
) {

    private static final Duration DEFAULT_THRESHOLD = Duration.ofHours(48);
    private static final Duration MAX_THRESHOLD = Duration.ofDays(30);

    public OrderDiagnosisProperties {
        shipmentDelayThreshold = validate(
                shipmentDelayThreshold,
                "shipmentDelayThreshold"
        );
        logisticsStallThreshold = validate(
                logisticsStallThreshold,
                "logisticsStallThreshold"
        );
    }

    private static Duration validate(Duration threshold, String name) {
        Duration effective = threshold == null ? DEFAULT_THRESHOLD : threshold;
        if (effective.isNegative() || effective.isZero()
                || effective.compareTo(MAX_THRESHOLD) > 0) {
            throw new IllegalArgumentException(
                    name + " must be positive and no greater than " + MAX_THRESHOLD
            );
        }
        return effective;
    }
}
