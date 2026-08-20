package cn.ethan.infrastructure.agent.action.worker;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionExecutor;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
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
    private final AgentItemStore items;
    private final AgentThreadEventGateway events;
    private final Clock clock;
    private final AgentWorkflowRunStore workflowRuns;
    private final Duration leaseDuration;
    private final Duration retryBaseDelay;
    private final Duration actionTimeout;
    private final ExecutorService actionExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-external-action");
        thread.setDaemon(true);
        return thread;
    });

    public ExternalActionWorker(
            ExternalActionCommandStore commands,
            ExternalActionExecutor executor,
            AgentItemStore items,
            AgentThreadEventGateway events,
            Clock clock,
            AgentWorkflowRunStore workflowRuns,
            @Value("${ai-agent.worker.lease-duration:PT30S}") Duration leaseDuration,
            @Value("${ai-agent.worker.retry-base-delay:PT5S}") Duration retryBaseDelay,
            @Value("${ai-agent.worker.action-timeout:PT30S}") Duration actionTimeout
    ) {
        this.commands = commands;
        this.executor = executor;
        this.items = items;
        this.events = events;
        this.clock = clock;
        this.workflowRuns = workflowRuns;
        this.leaseDuration = positive(leaseDuration, Duration.ofSeconds(30));
        this.retryBaseDelay = positive(retryBaseDelay, Duration.ofSeconds(5));
        this.actionTimeout = positive(actionTimeout, Duration.ofSeconds(30));
    }

    public int runOnce(int limit, Duration leaseDuration) {
        Instant now = clock.instant();
        String workerId = "worker-" + UUID.randomUUID();
        Duration effectiveLease = positive(leaseDuration, this.leaseDuration);
        List<ExternalActionCommandModel> claimed = commands.claimDue(
                now, now.plus(effectiveLease), workerId, limit);
        for (ExternalActionCommandModel command : claimed) {
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
        try {
            ExternalActionExecutor.ExternalActionResult result = executeWithTimeout(claimed);
            Instant now = clock.instant();
            ExternalActionCommandModel updated = result.success()
                    ? claimed.succeeded(now)
                    : result.retryable()
                    ? claimed.retryAt(now.plus(retryDelay(claimed.attemptCount())), result.code(), result.message(), now)
                    : claimed.failedPermanently(result.code(), result.message(), now);
            commands.update(updated);
            workflowRuns.find(updated.userId(), updated.runId()).ifPresent(run ->
                    workflowRuns.update(run.status(updated.status() == cn.ethan.core.agent.action.ExternalActionStatusEnum.SUCCEEDED
                            ? AgentWorkflowStatusEnum.COMPLETED
                            : updated.status() == cn.ethan.core.agent.action.ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED
                            ? AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED
                            : AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, now)));
            appendStatus(updated, result.message());
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            ExternalActionCommandModel updated = claimed.retryAt(
                    now.plus(retryDelay(claimed.attemptCount())), "WORKER_EXCEPTION", failure.getClass().getSimpleName(), now);
            commands.update(updated);
            workflowRuns.find(updated.userId(), updated.runId()).ifPresent(run ->
                    workflowRuns.update(run.status(updated.status() == cn.ethan.core.agent.action.ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED
                            ? AgentWorkflowStatusEnum.MANUAL_RETRY_REQUIRED
                            : AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION, now)));
            appendStatus(updated, "Worker 执行异常");
            LOGGER.warn("外部动作执行异常，commandId={}, errorType={}",
                    claimed.commandId(), failure.getClass().getSimpleName());
        }
    }

    private void appendStatus(ExternalActionCommandModel command, String message) {
        AgentItemModel item = new AgentItemModel(
                UUID.randomUUID().toString(), command.threadId(), command.turnId(), 0,
                AgentItemTypeEnum.EXTERNAL_ACTION_STATUS,
                command.commandId() + "|" + command.status().name() + "|" + message,
                clock.instant()
        );
        long sequence = items.appendItem(item);
        events.itemCreated(new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt()));
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
