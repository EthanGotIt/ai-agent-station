package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;

public interface IAfterSalesEventPublisher {

    void publish(AfterSalesDomainEvent event);
}
