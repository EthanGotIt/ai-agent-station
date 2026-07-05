package cn.ethan.ai.domain.agent.port.driving;

import cn.ethan.ai.domain.agent.model.AfterSalesDomainEvent;

public interface IAfterSalesEventHandler {

    boolean supports(String eventType);

    void handle(AfterSalesDomainEvent event);
}
