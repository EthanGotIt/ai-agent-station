package cn.ethan.infrastructure.agent.action.worker;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.commerce.order.LogisticsGateway;
import cn.ethan.core.commerce.order.OrderGateway;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import cn.ethan.core.commerce.order.OrderLookupStatusEnum;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.DisposableBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 类型职责：领取带租约的外部命令，执行后收敛状态并追加 Thread 轨迹。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Component
public final class ExternalActionWorker implements DisposableBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalActionWorker.class);

    private final ExternalActionCommandStore commands;
    private final ExternalActionExecutor executor;
    private final AgentThreadEventGateway events;
    private final Clock clock;
    private final ExternalActionOutcomeManager outcomes;
    private final Duration leaseDuration;
    private final Duration retryBaseDelay;
    private final Duration actionTimeout;
    private final AgentRuntimeMetrics metrics;
    private final OrderGateway orders;
    private final LogisticsGateway logistics;
    private final ObjectMapper objectMapper;
    private final ExecutorService actionExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-external-action");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public ExternalActionWorker(
            ExternalActionCommandStore commands,
            ExternalActionExecutor executor,
            AgentThreadEventGateway events,
            Clock clock,
            @Value("${ai-agent.worker.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${ai-agent.worker.retry-base-delay:PT5S}") Duration retryBaseDelay,
            @Value("${ai-agent.worker.action-timeout:PT30S}") Duration actionTimeout,
            AgentRuntimeMetrics metrics,
            ExternalActionOutcomeManager outcomes,
            OrderGateway orders,
            LogisticsGateway logistics,
            ObjectMapper objectMapper
    ) {
        this.commands = commands;
        this.executor = executor;
        this.events = events;
        this.clock = clock;
        this.outcomes = outcomes;
        this.leaseDuration = positive(leaseDuration, Duration.ofSeconds(30));
        this.retryBaseDelay = positive(retryBaseDelay, Duration.ofSeconds(5));
        this.actionTimeout = positive(actionTimeout, Duration.ofSeconds(30));
        this.metrics = metrics == null ? AgentRuntimeMetrics.noop() : metrics;
        this.orders = orders;
        this.logistics = logistics;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    /**
     * 保留内存测试的显式依赖构造边界；生产调用使用带事务的 OutcomeManager。
     */
    public ExternalActionWorker(
            ExternalActionCommandStore commands,
            ExternalActionExecutor executor,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            Clock clock,
            AgentWorkflowRunStore workflowRuns,
            Duration leaseDuration,
            Duration retryBaseDelay,
            Duration actionTimeout,
            AgentRuntimeMetrics metrics
    ) {
        this(commands, executor, events, clock, leaseDuration, retryBaseDelay, actionTimeout, metrics,
                new ExternalActionOutcomeManager(commands, items, turns, workflowRuns,
                        new ObjectMapper()), null, null, new ObjectMapper());
    }

    /** 为后置订单事实核验提供可控的内存测试构造边界。 */
    public ExternalActionWorker(
            ExternalActionCommandStore commands,
            ExternalActionExecutor executor,
            AgentItemStore items,
            AgentTurnStore turns,
            AgentThreadEventGateway events,
            Clock clock,
            AgentWorkflowRunStore workflowRuns,
            Duration leaseDuration,
            Duration retryBaseDelay,
            Duration actionTimeout,
            AgentRuntimeMetrics metrics,
            OrderGateway orders,
            LogisticsGateway logistics
    ) {
        this(commands, executor, events, clock, leaseDuration, retryBaseDelay, actionTimeout, metrics,
                new ExternalActionOutcomeManager(commands, items, turns, workflowRuns,
                        new ObjectMapper()), orders, logistics, new ObjectMapper());
    }

    public int runOnce(int limit, Duration leaseDuration) {
        Instant now = clock.instant();
        String workerId = "worker-" + UUID.randomUUID();
        Duration effectiveLease = positive(leaseDuration, this.leaseDuration);
        List<ExternalActionCommandModel> claimed = commands.claimDue(
                now, now.plus(effectiveLease), workerId, limit);
        for (ExternalActionCommandModel command : claimed) {
            // attemptCount>1 同时覆盖重试和过期 Lease 接管，指标只用于低基数运行态势
            if (command.attemptCount() > 1) {
                metrics.observeLeaseTakeover();
            }
            execute(command);
        }
        return claimed.size();
    }

    /** 由应用调度器周期领取命令；远程调用始终发生在本地事务之外。 */
    @Scheduled(
            fixedDelayString = "${AI_AGENT_WORKER_POLL_MILLIS:5000}",
            initialDelayString = "${AI_AGENT_WORKER_INITIAL_DELAY_MILLIS:10000}"
    )
    public void poll() {
        runOnce(8, leaseDuration);
    }

    @Override
    public void destroy() {
        actionExecutor.shutdownNow();
    }

    private void execute(ExternalActionCommandModel claimed) {
        ExternalActionExecutor.ExternalActionResult result;
        try {
            result = executeWithTimeout(claimed);
        } catch (RuntimeException failure) {
            retryAfterExecutionFailure(claimed, failure);
            return;
        }
        if (result == null) {
            retryAfterExecutionFailure(claimed, new IllegalStateException("外部动作执行器返回空结果"));
            return;
        }

        Instant now = clock.instant();
        ExternalActionCommandModel updated = result.success()
                ? claimed.succeeded(now)
                : result.retryable()
                ? claimed.retryAt(now.plus(retryDelay(claimed.attemptCount())), result.code(), result.message(), now)
                : claimed.failedPermanently(result.code(), result.message(), now);
        if (!result.success() && result.retryable()) {
            metrics.observeWorkerRetry();
        }
        try {
            ExternalActionOutcomeManager.Verification verification = result.success()
                    ? verifyAfterSuccess(claimed)
                    : null;
            ExternalActionOutcomeManager.Projection projection = outcomes.transition(
                    claimed, updated, result.code(), result.message(), clock, verification);
            if (projection == null) {
                LOGGER.info("外部动作 Lease 已失效，停止投影，commandId={}, version={}",
                        claimed.commandId(), claimed.version());
                return;
            }
            publishSafely(projection);
        } catch (RuntimeException failure) {
            // 本地事务失败时保持 PROCESSING，等待 Lease 到期后的相同幂等键恢复；不得重做远程动作。
            LOGGER.warn("外部动作本地投影失败，等待 Lease 恢复，commandId={}, errorType={}",
                    claimed.commandId(), failure.getClass().getSimpleName());
        }
    }

    private void retryAfterExecutionFailure(ExternalActionCommandModel claimed, RuntimeException failure) {
        Instant now = clock.instant();
        metrics.observeWorkerRetry();
        ExternalActionCommandModel updated = claimed.retryAt(
                now.plus(retryDelay(claimed.attemptCount())), "WORKER_EXCEPTION",
                failure.getClass().getSimpleName(), now);
        try {
            ExternalActionOutcomeManager.Projection projection = outcomes.transition(
                    claimed, updated, "WORKER_EXCEPTION", failure.getClass().getSimpleName(), clock);
            if (projection == null) {
                LOGGER.info("异常收敛时外部动作 Lease 已失效，停止投影，commandId={}, version={}",
                        claimed.commandId(), claimed.version());
                return;
            }
            publishSafely(projection);
            LOGGER.warn("外部动作执行异常，commandId={}, errorType={}",
                    claimed.commandId(), failure.getClass().getSimpleName());
        } catch (RuntimeException projectionFailure) {
            LOGGER.warn("外部动作异常结果无法本地收敛，等待 Lease 恢复，commandId={}, errorType={}",
                    claimed.commandId(), projectionFailure.getClass().getSimpleName());
        }
    }

    /** 外部动作成功后在本地事务外核验最新订单事实；失败只形成可见回执，不重放动作。 */
    private ExternalActionOutcomeManager.Verification verifyAfterSuccess(ExternalActionCommandModel command) {
        Instant verifiedAt = clock.instant();
        String orderId = orderId(command.payloadJson());
        if (orders == null || orderId == null) {
            return ExternalActionOutcomeManager.Verification.unavailable(
                    "操作已受理、最新状态暂未核验", verifiedAt);
        }
        try {
            OrderLookupResultModel lookup = orders.findOrder(orderId, command.userId());
            if (command.type() == ExternalActionTypeEnum.DELETE_ORDER
                    && lookup != null && lookup.status() == OrderLookupStatusEnum.NOT_FOUND) {
                return ExternalActionOutcomeManager.Verification.fromFacts(
                        null, List.of(), true, "订单记录已删除", verifiedAt);
            }
            if (lookup == null || lookup.status() != OrderLookupStatusEnum.FOUND || lookup.order() == null) {
                return ExternalActionOutcomeManager.Verification.unavailable(
                        "操作已受理、最新状态暂未核验", verifiedAt);
            }
            var trace = logistics == null ? List.<cn.ethan.core.commerce.order.LogisticsEventModel>of()
                    : logistics.findTrace(orderId, command.userId());
            return ExternalActionOutcomeManager.Verification.found(lookup.order(), trace, verifiedAt);
        } catch (RuntimeException failure) {
            LOGGER.warn("外部动作成功但后置订单核验失败，commandId={}, errorType={}",
                    command.commandId(), failure.getClass().getSimpleName());
            return ExternalActionOutcomeManager.Verification.unavailable(
                    "操作已受理、最新状态暂未核验", verifiedAt);
        }
    }

    private String orderId(String payloadJson) {
        try {
            String value = objectMapper.readTree(payloadJson == null ? "{}" : payloadJson)
                    .path("orderId").asString("").strip();
            return value.isBlank() ? null : value;
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private void publishSafely(ExternalActionOutcomeManager.Projection projection) {
        try {
            for (AgentItemModel item : projection.items()) {
                events.itemCreated(item);
            }
        } catch (RuntimeException failure) {
            // Item 已经持久化；SSE 断线可从游标回放，不能因发布失败再次执行外部动作。
            LOGGER.warn("外部动作事实已提交但实时事件发布失败，commandId={}, errorType={}",
                    projection.command().commandId(), failure.getClass().getSimpleName());
        }
    }

    private ExternalActionExecutor.ExternalActionResult executeWithTimeout(ExternalActionCommandModel command) {
        Future<ExternalActionExecutor.ExternalActionResult> future = actionExecutor.submit(
                () -> executor.execute(command));
        try {
            return future.get(actionTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return new ExternalActionExecutor.ExternalActionResult(
                    false, true, "EXTERNAL_ACTION_TIMEOUT", "外部动作执行超时");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ExternalActionExecutor.ExternalActionResult(
                    false, true, "WORKER_INTERRUPTED", "Worker 线程被中断");
        } catch (Exception failure) {
            throw new IllegalStateException("外部动作执行器失败", failure);
        }
    }

    private Duration retryDelay(int attemptCount) {
        int exponent = Math.min(8, Math.max(0, attemptCount - 1));
        long multiplier = 1L << exponent;
        long seconds = Math.min(Duration.ofMinutes(10).toSeconds(), retryBaseDelay.toSeconds() * multiplier);
        return Duration.ofSeconds(Math.max(1L, seconds));
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
