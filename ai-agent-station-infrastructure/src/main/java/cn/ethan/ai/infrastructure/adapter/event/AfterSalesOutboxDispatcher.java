package cn.ethan.ai.infrastructure.adapter.event;

import cn.ethan.ai.domain.agent.port.driven.IAfterSalesEventPublisher;
import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;
import cn.ethan.ai.infrastructure.dao.AfterSalesOutboxMapper;
import cn.ethan.ai.infrastructure.dao.po.AfterSalesOutboxPO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AfterSalesOutboxDispatcher {

    private final AfterSalesOutboxMapper outboxMapper;
    private final IAfterSalesEventPublisher publisher;
    private final String workerId = "outbox-" + UUID.randomUUID();
    private final boolean schedulingEnabled;
    private final int maxAttempts;

    public AfterSalesOutboxDispatcher(AfterSalesOutboxMapper outboxMapper,
                                      IAfterSalesEventPublisher publisher,
                                      @Value("${ai-agent.after-sales.outbox.scheduling-enabled:false}") boolean schedulingEnabled,
                                      @Value("${ai-agent.after-sales.outbox.max-attempts:5}") int maxAttempts) {
        this.outboxMapper = outboxMapper;
        this.publisher = publisher;
        this.schedulingEnabled = schedulingEnabled;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${ai-agent.after-sales.outbox.fixed-delay:1000}")
    public void scheduledDispatch() {
        if (schedulingEnabled) {
            dispatchBatch(50, LocalDateTime.now());
        }
    }

    public DispatchResult dispatchBatch(int batchSize, LocalDateTime now) {
        int delivered = 0;
        int retried = 0;
        int dead = 0;
        List<AfterSalesOutboxPO> events = outboxMapper.selectDispatchable(now, batchSize);
        for (AfterSalesOutboxPO event : events) {
            if (outboxMapper.claim(event.getEventId(), workerId, now.plusSeconds(30), now) == 0) {
                continue;
            }
            try {
                publisher.publish(new AfterSalesDomainEvent(
                        event.getEventId(), event.getAggregateId(), event.getEventType(), event.getPayload()));
                outboxMapper.markDelivered(event.getEventId(), workerId, now);
                delivered++;
            } catch (RuntimeException error) {
                int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
                boolean exhausted = retryCount >= maxAttempts;
                String status = exhausted ? "DEAD" : "RETRY";
                LocalDateTime nextAttemptAt = exhausted ? now : now.plusSeconds(backoffSeconds(retryCount));
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                outboxMapper.markFailed(event.getEventId(), workerId, status, retryCount,
                        nextAttemptAt, truncate(message, 1024));
                if (exhausted) {
                    dead++;
                } else {
                    retried++;
                }
            }
        }
        return new DispatchResult(events.size(), delivered, retried, dead);
    }

    private long backoffSeconds(int retryCount) {
        return Math.min(60, 1L << Math.min(6, Math.max(0, retryCount - 1)));
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    public record DispatchResult(int selected, int delivered, int retried, int dead) {
    }
}
