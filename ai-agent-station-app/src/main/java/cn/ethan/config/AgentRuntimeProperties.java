package cn.ethan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent 运行参数：集中约束请求生命周期、流式超时和执行线程池资源边界。
 *
 * @author ethan
 * @date 2026-08-05
 */
@ConfigurationProperties(prefix = "ai-agent.runtime")
public record AgentRuntimeProperties(
        Duration requestTerminalTtl,
        Duration streamTimeout,
        QueueProperties queue,
        ExecutorProperties executor
) {

    private static final Duration DEFAULT_REQUEST_TERMINAL_TTL = Duration.ofMinutes(10);
    private static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(245);
    private static final Duration MAX_TTL = Duration.ofHours(24);
    private static final Duration MAX_STREAM_TIMEOUT = Duration.ofMinutes(5);

    public AgentRuntimeProperties {
        requestTerminalTtl = validateDuration(
                requestTerminalTtl,
                DEFAULT_REQUEST_TERMINAL_TTL,
                MAX_TTL,
                "requestTerminalTtl"
        );
        streamTimeout = validateDuration(
                streamTimeout,
                DEFAULT_STREAM_TIMEOUT,
                MAX_STREAM_TIMEOUT,
                "streamTimeout"
        );
        queue = queue == null
                ? new QueueProperties(null, null, null)
                : queue;
        executor = executor == null
                ? new ExecutorProperties(null, null, null, null)
                : executor;
        if (executor.queueCapacity() < queue.maxPendingGlobal()) {
            throw new IllegalArgumentException(
                    "executor.queueCapacity must not be less than queue.maxPendingGlobal"
            );
        }
    }

    /**
     * Session 队列参数：约束单 Session 与全局待执行请求规模。
     */
    public record QueueProperties(
            Integer maxPendingPerSession,
            Integer maxPendingGlobal,
            Duration waitTimeout
    ) {

        private static final int DEFAULT_MAX_PENDING_PER_SESSION = 4;
        private static final int DEFAULT_MAX_PENDING_GLOBAL = 256;
        private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofMinutes(2);

        public QueueProperties {
            maxPendingPerSession = maxPendingPerSession == null
                    ? DEFAULT_MAX_PENDING_PER_SESSION
                    : maxPendingPerSession;
            maxPendingGlobal = maxPendingGlobal == null
                    ? DEFAULT_MAX_PENDING_GLOBAL
                    : maxPendingGlobal;
            waitTimeout = waitTimeout == null ? DEFAULT_WAIT_TIMEOUT : waitTimeout;

            if (maxPendingPerSession < 1 || maxPendingPerSession > 100) {
                throw new IllegalArgumentException(
                        "queue.maxPendingPerSession must be between 1 and 100"
                );
            }
            if (maxPendingGlobal < maxPendingPerSession || maxPendingGlobal > 10_000) {
                throw new IllegalArgumentException(
                        "queue.maxPendingGlobal must be between maxPendingPerSession and 10000"
                );
            }
            if (waitTimeout.isZero()
                    || waitTimeout.isNegative()
                    || waitTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalArgumentException(
                        "queue.waitTimeout must be positive and no greater than PT10M"
                );
            }
        }
    }

    public long streamTimeoutMillis() {
        return streamTimeout.toMillis();
    }

    private static Duration validateDuration(Duration value, Duration defaultValue,
                                             Duration maximum, String name) {
        Duration effective = value == null ? defaultValue : value;
        if (effective.isZero() || effective.isNegative() || effective.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be positive and no greater than " + maximum
            );
        }
        return effective;
    }

    public record ExecutorProperties(
            Integer corePoolSize,
            Integer maxPoolSize,
            Integer queueCapacity,
            Duration awaitTermination
    ) {

        private static final int DEFAULT_CORE_POOL_SIZE = 4;
        private static final int DEFAULT_MAX_POOL_SIZE = 16;
        private static final int DEFAULT_QUEUE_CAPACITY = 256;
        private static final Duration DEFAULT_AWAIT_TERMINATION = Duration.ofSeconds(10);

        public ExecutorProperties {
            corePoolSize = corePoolSize == null ? DEFAULT_CORE_POOL_SIZE : corePoolSize;
            maxPoolSize = maxPoolSize == null ? DEFAULT_MAX_POOL_SIZE : maxPoolSize;
            queueCapacity = queueCapacity == null ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
            awaitTermination = awaitTermination == null
                    ? DEFAULT_AWAIT_TERMINATION
                    : awaitTermination;

            if (corePoolSize < 1 || corePoolSize > 64) {
                throw new IllegalArgumentException("executor.corePoolSize must be between 1 and 64");
            }
            if (maxPoolSize < corePoolSize || maxPoolSize > 256) {
                throw new IllegalArgumentException(
                        "executor.maxPoolSize must be between corePoolSize and 256"
                );
            }
            if (queueCapacity < 0 || queueCapacity > 10_000) {
                throw new IllegalArgumentException(
                        "executor.queueCapacity must be between 0 and 10000"
                );
            }
            if (awaitTermination.isNegative()
                    || awaitTermination.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalArgumentException(
                        "executor.awaitTermination must be between PT0S and PT1M"
                );
            }
        }

        public int awaitTerminationSeconds() {
            return Math.toIntExact(awaitTermination.toSeconds());
        }
    }
}
