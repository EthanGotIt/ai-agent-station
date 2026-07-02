package cn.ethan.ai.domain.agent.model;

public record AfterSalesOrderSnapshot(String orderId,
                                      String ownerId,
                                      String status,
                                      Integer daysSinceDelivery) {
}
