package cn.ethan.ai.domain.agent.model;

public record RefundGatewayResult(boolean success,
                                  boolean idempotentReplay,
                                  String reason) {
}
