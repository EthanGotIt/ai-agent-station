package cn.ethan.ai.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 售后 Agent 运行指标的基础设施适配器。
 */
public final class AfterSalesRuntimeMetrics {

    private static final AfterSalesRuntimeMetrics NOOP = new AfterSalesRuntimeMetrics(null);

    private final MeterRegistry registry;

    public AfterSalesRuntimeMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static AfterSalesRuntimeMetrics noop() {
        return NOOP;
    }

    public void recordCheckpoint(String kind, String stage) {
        increment("after_sales.checkpoint.total", "kind", kind, "stage", stage);
    }

    public void recordBoundary(String stage) {
        increment("after_sales.turn.boundary.total", "stage", stage);
    }

    public void recordReplan(String reason) {
        increment("after_sales.replan.total", "reason", reason == null ? "unknown" : reason);
    }

    public void recordResumeAcquire(boolean acquired) {
        increment("after_sales.resume.acquire.total", "outcome", acquired ? "acquired" : "conflict");
    }

    public void recordRefund(String outcome) {
        increment("after_sales.refund.total", "outcome", outcome);
    }

    public void recordModelPlan(long elapsedNanos, String outcome) {
        recordDuration("after_sales.model.plan.duration", elapsedNanos, "outcome", outcome);
    }

    public void recordPlanDecision(String action, String outcome, String reasonCode) {
        increment("after_sales.plan.decision.total",
                "action", action == null ? "none" : action,
                "outcome", outcome == null ? "unknown" : outcome,
                "reason", reasonCode == null ? "none" : reasonCode);
    }

    public void recordPlanFallback(String reason) {
        increment("after_sales.plan.fallback.total", "reason", reason == null ? "unknown" : reason);
    }

    public void recordEvidenceTool(String tool, long elapsedNanos, String outcome) {
        recordDuration("after_sales.evidence.tool.duration", elapsedNanos,
                "tool", tool == null ? "unknown" : tool,
                "outcome", outcome == null ? "unknown" : outcome);
    }

    public void recordTool(long elapsedNanos, String outcome) {
        recordDuration("after_sales.tool.duration", elapsedNanos,
                "outcome", outcome == null ? "unknown" : outcome);
    }

    private void increment(String name, String... tags) {
        if (registry != null) {
            registry.counter(name, tags).increment();
        }
    }

    private void recordDuration(String name, long elapsedNanos, String... tags) {
        if (registry != null) {
            Timer.builder(name).tags(tags).register(registry).record(elapsedNanos, TimeUnit.NANOSECONDS);
        }
    }
}
