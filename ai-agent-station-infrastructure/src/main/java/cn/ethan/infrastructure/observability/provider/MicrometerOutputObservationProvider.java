package cn.ethan.infrastructure.observability.provider;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.port.OutputObservationProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Micrometer 输出观测提供器：记录事件、耗时、Token 和归一化错误指标。
 *
 * @author ethan
 * @date 2026-08-05
 */
@Component
public final class MicrometerOutputObservationProvider implements OutputObservationProvider {

    private final MeterRegistry meterRegistry;

    public MicrometerOutputObservationProvider(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordEvent(OutputEventTypeEnum type) {
        meterRegistry.counter(
                "ai.agent.output.events",
                "type",
                type.name().toLowerCase()
        ).increment();
    }

    @Override
    public void recordCompletion(String executorId, AgentStatusEnum status, Duration duration,
                                 int inputTokens, int outputTokens) {
        meterRegistry.timer(
                "ai.agent.request.duration",
                "executor",
                executorId,
                "status",
                status.name().toLowerCase()
        ).record(duration.toNanos(), TimeUnit.NANOSECONDS);
        meterRegistry.counter(
                "ai.agent.tokens",
                "direction",
                "input"
        ).increment(inputTokens);
        meterRegistry.counter(
                "ai.agent.tokens",
                "direction",
                "output"
        ).increment(outputTokens);
    }

    @Override
    public void recordError(String errorCode, Duration duration) {
        meterRegistry.counter(
                "ai.agent.errors",
                "code",
                errorCode
        ).increment();
        meterRegistry.timer(
                "ai.agent.error.duration",
                "code",
                errorCode
        ).record(duration.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordWorkflowTransition(String workflowId, String status) {
        meterRegistry.counter(
                "ai.agent.workflow.transitions",
                "workflow", workflowId,
                "status", status
        ).increment();
    }

    @Override
    public void recordMemoryExtraction(String outcome) {
        meterRegistry.counter("ai.agent.memory.extraction", "outcome", outcome).increment();
    }

    @Override
    public void recordMemoryRetrieval(String consumer, int entryCount, int characterCount) {
        meterRegistry.counter("ai.agent.memory.retrieval.entries", "consumer", consumer)
                .increment(Math.max(entryCount, 0));
        meterRegistry.counter("ai.agent.memory.retrieval.characters", "consumer", consumer)
                .increment(Math.max(characterCount, 0));
    }

    @Override
    public void recordIntervention(String outcome, Duration waitDuration) {
        meterRegistry.counter("ai.agent.intervention", "outcome", outcome).increment();
        if (waitDuration != null) {
            meterRegistry.timer("ai.agent.intervention.wait", "outcome", outcome)
                    .record(Math.max(waitDuration.toNanos(), 0), TimeUnit.NANOSECONDS);
        }
    }
}
