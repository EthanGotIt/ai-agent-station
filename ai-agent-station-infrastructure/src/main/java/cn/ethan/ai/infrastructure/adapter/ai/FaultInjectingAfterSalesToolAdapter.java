package cn.ethan.ai.infrastructure.adapter.ai;

import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.model.AfterSalesToolCapability;
import cn.ethan.ai.domain.agent.model.AfterSalesToolContext;
import cn.ethan.ai.domain.agent.model.AfterSalesToolRequest;
import cn.ethan.ai.domain.agent.model.AfterSalesToolResult;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

@Primary
@Component
@ConditionalOnProperty(name = "ai-agent.after-sales.fault-injection.enabled", havingValue = "true")
public class FaultInjectingAfterSalesToolAdapter implements IAfterSalesToolPort {

    private final IAfterSalesToolPort delegate;
    private final String mode;
    private final int failureCount;
    private final AtomicInteger attempts = new AtomicInteger();

    public FaultInjectingAfterSalesToolAdapter(
            @Qualifier("springAiAfterSalesToolAdapter") IAfterSalesToolPort delegate,
            @Value("${ai-agent.after-sales.fault-injection.mode:TIMEOUT}") String mode,
            @Value("${ai-agent.after-sales.fault-injection.failure-count:1}") int failureCount) {
        this.delegate = delegate;
        this.mode = mode == null ? "TIMEOUT" : mode.trim().toUpperCase();
        this.failureCount = Math.max(0, failureCount);
    }

    @Override
    public Set<AfterSalesToolCapability> supportedTools() {
        return delegate.supportedTools();
    }

    @Override
    public AfterSalesToolResult executeReadOnly(AfterSalesToolRequest request, AfterSalesToolContext context) {
        if (shouldInject()) {
            return switch (mode) {
                case "RATE_LIMITED" -> AfterSalesToolResult.failure("", "RATE_LIMITED", "injected rate limit");
                case "STATE_CONFLICT" -> AfterSalesToolResult.failure("", "STATE_CONFLICT", "injected state conflict");
                case "FORBIDDEN" -> AfterSalesToolResult.failure("", "FORBIDDEN", "injected forbidden");
                default -> AfterSalesToolResult.failure("", "TIMEOUT", "injected timeout");
            };
        }
        return delegate.executeReadOnly(request, context);
    }

    private boolean shouldInject() {
        return attempts.incrementAndGet() <= failureCount;
    }
}
