package cn.ethan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 退款任务配置：限制本地异步退款的批量、租约、退避与重试上限。
 *
 * @author ethan
 * @date 2026-08-12
 */
@ConfigurationProperties(prefix = "ai-agent.after-sales.refund-worker")
public record RefundWorkerProperties(
        Duration pollInterval,
        Duration initialDelay,
        Integer batchSize,
        Integer maxAttempts,
        Duration retryDelay,
        Duration leaseDuration
) {

    public RefundWorkerProperties {
        pollInterval = duration(pollInterval, Duration.ofSeconds(5), "pollInterval");
        initialDelay = duration(initialDelay, Duration.ofSeconds(10), "initialDelay");
        retryDelay = duration(retryDelay, Duration.ofSeconds(15), "retryDelay");
        leaseDuration = duration(leaseDuration, Duration.ofSeconds(30), "leaseDuration");
        batchSize = bounded(batchSize, 8, 1, 100, "batchSize");
        maxAttempts = bounded(maxAttempts, 3, 1, 10, "maxAttempts");
    }

    private static Duration duration(Duration value, Duration defaultValue, String name) {
        Duration effective = value == null ? defaultValue : value;
        if (effective.isZero() || effective.isNegative() || effective.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(name + " must be between PT0S and PT1H");
        }
        return effective;
    }

    private static int bounded(Integer value, int defaultValue, int min, int max, String name) {
        int effective = value == null ? defaultValue : value;
        if (effective < min || effective > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return effective;
    }
}
