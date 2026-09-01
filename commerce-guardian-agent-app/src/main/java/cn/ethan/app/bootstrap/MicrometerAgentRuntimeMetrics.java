package cn.ethan.app.bootstrap;

import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;

/**
 * 类型职责：将 Agent Runtime 观测转换为无用户、Thread 或订单高基数标签的 Micrometer 指标。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class MicrometerAgentRuntimeMetrics implements AgentRuntimeMetrics {

    private final MeterRegistry registry;

    public MicrometerAgentRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void observeQueueWait(Duration duration) {
        registry.timer("agent.queue.wait").record(duration);
    }

    @Override
    public void observeTurn(Duration duration, String status) {
        registry.timer("agent.turn.duration", "status", safe(status)).record(duration);
    }

    @Override
    public void observeContext(int estimatedTokens, boolean compressed, boolean degraded) {
        registry.summary("agent.context.tokens", "compressed", Boolean.toString(compressed),
                "degraded", Boolean.toString(degraded)).record(estimatedTokens);
    }

    @Override
    public void observeFailure(String category) {
        registry.counter("agent.runtime.failure", "category", safe(category)).increment();
    }

    @Override
    public void observeTool(Duration duration, String status) {
        registry.timer("agent.tool.duration", "status", safe(status)).record(duration);
    }

    @Override
    public void observeWorkflowWait(Duration duration) {
        registry.timer("agent.workflow.wait").record(duration);
    }

    @Override
    public void observeWorkerRetry() {
        registry.counter("agent.worker.retry").increment();
    }

    @Override
    public void observeLeaseTakeover() {
        registry.counter("agent.worker.lease.takeover").increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
