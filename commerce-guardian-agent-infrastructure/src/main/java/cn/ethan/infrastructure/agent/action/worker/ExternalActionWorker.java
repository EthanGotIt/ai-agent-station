package cn.ethan.infrastructure.agent.action.worker;

import cn.ethan.core.agent.action.model.ExternalActionCommandModel;
import cn.ethan.core.agent.action.port.ExternalActionCommandStore;
import cn.ethan.core.agent.action.port.ExternalActionExecutor;
import cn.ethan.core.agent.thread.enums.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.model.AgentItemModel;
import cn.ethan.core.agent.thread.port.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.port.AgentThreadStore;
import cn.ethan.core.agent.thread.port.AgentWorkflowRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 类型职责：领取带租约的外部命令，执行后收敛状态并追加 Thread 轨迹。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Component
public final class ExternalActionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalActionWorker.class);

    private final ExternalActionCommandStore commands;
    private final ExternalActionExecutor executor;
    private final AgentThreadStore threads;
    private final AgentThreadEventGateway events;
    private final Clock clock;
    private final AgentWorkflowRunStore workflowRuns;

    public ExternalActionWorker(
            ExternalActionCommandStore commands,
            ExternalActionExecutor executor,
            AgentThreadStore threads,
            AgentThreadEventGateway events,
            Clock clock,
            AgentWorkflowRunStore workflowRuns
    ) {
        this.commands = commands;
        this.executor = executor;
        this.threads = threads;
        this.events = events;
        this.clock = clock;
        this.workflowRuns = workflowRuns;
    }

    public int runOnce(int limit, Duration leaseDuration) {
        Instant now = clock.instant();
        String workerId = "worker-" + UUID.randomUUID();
        List<ExternalActionCommandModel> claimed = commands.claimDue(
                now, now.plus(leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration), workerId, limit);
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
        runOnce(8, Duration.ofSeconds(30));
    }

    private void execute(ExternalActionCommandModel claimed) {
        try {
            ExternalActionExecutor.ExternalActionResult result = executor.execute(claimed);
            Instant now = clock.instant();
            ExternalActionCommandModel updated = result.success()
                    ? claimed.succeeded(now)
                    : claimed.retryAt(now.plusSeconds(15), result.code(), result.message(), now);
            commands.update(updated);
            workflowRuns.find(updated.userId(), updated.runId()).ifPresent(run ->
                    workflowRuns.update(run.status(updated.status().name().equals("SUCCEEDED")
                            ? "COMPLETED" : updated.status().name(), now)));
            appendStatus(updated, result.message());
        } catch (RuntimeException failure) {
            Instant now = clock.instant();
            ExternalActionCommandModel updated = claimed.retryAt(
                    now.plusSeconds(15), "WORKER_EXCEPTION", failure.getClass().getSimpleName(), now);
            commands.update(updated);
            workflowRuns.find(updated.userId(), updated.runId()).ifPresent(run ->
                    workflowRuns.update(run.status(updated.status().name(), now)));
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
        long sequence = threads.appendItem(item);
        events.itemCreated(new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt()));
    }
}
