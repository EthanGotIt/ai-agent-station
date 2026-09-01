package cn.ethan.core.agent.execution;

import java.time.Duration;

/**
 * 类型职责：提供低基数运行时观测端口，Core 不依赖具体指标供应商。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentRuntimeMetrics {

    void observeQueueWait(Duration duration);

    void observeTurn(Duration duration, String status);

    void observeContext(int estimatedTokens, boolean compressed, boolean degraded);

    void observeFailure(String category);

    default void observeTool(Duration duration, String status) {
    }

    default void observeWorkflowWait(Duration duration) {
    }

    default void observeWorkerRetry() {
    }

    default void observeLeaseTakeover() {
    }

    static AgentRuntimeMetrics noop() {
        return new AgentRuntimeMetrics() {
            @Override public void observeQueueWait(Duration duration) { }
            @Override public void observeTurn(Duration duration, String status) { }
            @Override public void observeContext(int estimatedTokens, boolean compressed, boolean degraded) { }
            @Override public void observeFailure(String category) { }
        };
    }
}
