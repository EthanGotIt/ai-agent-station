package cn.ethan.ai.domain.agent.model;

public record AfterSalesDomainEvent(String eventId,
                                    String aggregateId,
                                    String eventType,
                                    String payload) {
}
