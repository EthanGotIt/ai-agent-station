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
    public void observeOutputSettlement(int reservedTokens, int chargedTokens, boolean conservative) {
        registry.summary("agent.model.output.reserved").record(reservedTokens);
        registry.summary("agent.model.output.charged").record(chargedTokens);
        if (conservative) {
            registry.counter("agent.model.output.conservative").increment();
        }
    }

    @Override
    public void observeContextCompaction(
            int beforeEstimatedTokens, int afterEstimatedTokens, int prunedToolResults, boolean summaryApplied
    ) {
        registry.summary("agent.context.compaction.before").record(beforeEstimatedTokens);
        registry.summary("agent.context.compaction.after").record(afterEstimatedTokens);
        registry.summary("agent.context.compaction.pruned-tools").record(prunedToolResults);
        registry.counter("agent.context.compaction", "summary", Boolean.toString(summaryApplied)).increment();
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

    @Override
    public void observeWorkflow(String orchestrationVersion, String event) {
        registry.counter("agent.workflow.lifecycle", "orchestration", safe(orchestrationVersion),
                "event", safe(event)).increment();
    }

    @Override
    public void observeWorkflowFacts(String orchestrationVersion, String result) {
        registry.counter("agent.workflow.facts", "orchestration", safe(orchestrationVersion),
                "result", safe(result)).increment();
    }

    @Override
    public void observeWorkflowCommand(String orchestrationVersion, boolean deduplicated) {
        registry.counter("agent.workflow.command", "orchestration", safe(orchestrationVersion),
                "deduplicated", Boolean.toString(deduplicated)).increment();
    }

    @Override
    public void observeWorkflowWorker(String orchestrationVersion, String result) {
        registry.counter("agent.workflow.worker", "orchestration", safe(orchestrationVersion),
                "result", safe(result)).increment();
    }

    @Override
    public void observeWorkflowRecovery(String orchestrationVersion, String result) {
        registry.counter("agent.workflow.recovery", "orchestration", safe(orchestrationVersion),
                "result", safe(result)).increment();
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
