package cn.ethan.ai.domain.agent.model;

public record AfterSalesRefundResult(boolean success,
                                     boolean idempotentReplay,
                                     String commandId,
                                     String reason) {
}
