package cn.ethan.ai.domain.agent.model;

import java.time.LocalDateTime;

/**
 * 物流证据的最小标准化视图，不包含地址和物流单号。
 */
public record AfterSalesLogisticsSnapshot(String orderId,
                                          String deliveryStatus,
                                          LocalDateTime deliveredAt,
                                          String returnStatus) {
}
