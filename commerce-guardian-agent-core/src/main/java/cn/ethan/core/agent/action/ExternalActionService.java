package cn.ethan.core.agent.action;

import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadNotFoundException;

import java.time.Clock;

/**
 * 类型职责：收敛外部动作的人工恢复边界，保证重试沿用原命令和幂等键。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class ExternalActionService {

    private final ExternalActionCommandStore commands;
    private final Clock clock;

    public ExternalActionService(ExternalActionCommandStore commands, Clock clock) {
        this.commands = commands;
        this.clock = clock;
    }

    public ExternalActionCommandModel retry(String userId, String runId) {
        ExternalActionCommandModel command = commands.findByRunId(userId, runId)
                .orElseThrow(() -> new AgentThreadNotFoundException(runId));
        if (command.status() != ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED) {
            throw new AgentThreadConflictException("ACTION_NOT_RETRYABLE", "外部动作当前不需要人工重试");
        }
        ExternalActionCommandModel retried = command.manualRetry(clock.instant());
        if (!commands.update(command, retried)) {
            throw new AgentThreadConflictException("ACTION_VERSION_CONFLICT", "外部动作已被其他恢复流程推进");
        }
        return retried;
    }
}
