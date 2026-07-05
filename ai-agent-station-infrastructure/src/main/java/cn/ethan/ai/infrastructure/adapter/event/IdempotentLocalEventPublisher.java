package cn.ethan.ai.infrastructure.adapter.event;

import cn.ethan.ai.domain.agent.port.driving.IAfterSalesEventHandler;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesEventPublisher;
import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;
import cn.ethan.ai.infrastructure.dao.AfterSalesOutboxMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Component
public class IdempotentLocalEventPublisher implements IAfterSalesEventPublisher {

    static final String CONSUMER_NAME = "after-sales-local-consumer";

    private final AfterSalesOutboxMapper outboxMapper;
    private final List<IAfterSalesEventHandler> handlers;
    private final TransactionTemplate transactionTemplate;

    public IdempotentLocalEventPublisher(AfterSalesOutboxMapper outboxMapper,
                                         List<IAfterSalesEventHandler> handlers,
                                         @Qualifier("mysqlTransactionTemplate") TransactionTemplate transactionTemplate) {
        this.outboxMapper = outboxMapper;
        this.handlers = handlers;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void publish(AfterSalesDomainEvent event) {
        transactionTemplate.executeWithoutResult(status -> consumeOnce(event));
    }

    private void consumeOnce(AfterSalesDomainEvent event) {
        if (outboxMapper.insertConsumerReceipt(event.eventId(), CONSUMER_NAME) == 0) {
            return;
        }
        handlers.stream()
                .filter(handler -> handler.supports(event.eventType()))
                .forEach(handler -> handler.handle(event));
        outboxMapper.markConsumerSuccess(event.eventId(), CONSUMER_NAME);
    }
}
