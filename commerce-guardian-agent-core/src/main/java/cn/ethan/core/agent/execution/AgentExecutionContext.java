package cn.ethan.core.agent.execution;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 类型职责：在 Turn、模型协调和 Tool 边界之间传播取消与截止时间，不回滚已经提交的副作用。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class AgentExecutionContext {

    private final Clock clock;
    private final Instant deadline;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public AgentExecutionContext(Clock clock, Instant deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get() || clock.instant().compareTo(deadline) >= 0;
    }

    public void checkActive() {
        if (cancelled()) {
            throw new AgentExecutionCancelledException("Agent Turn 已取消或超过执行截止时间");
        }
    }

    public Instant deadline() {
        return deadline;
    }
}
