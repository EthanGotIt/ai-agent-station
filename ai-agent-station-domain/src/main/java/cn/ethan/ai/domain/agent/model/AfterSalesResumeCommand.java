package cn.ethan.ai.domain.agent.model;

public record AfterSalesResumeCommand(String runId,
                                      String checkpointId,
                                      ResumeAction action,
                                      String orderId,
                                      String refundReason) {

    public enum ResumeAction {
        SUPPLY_INFO,
        APPROVE,
        REJECT
    }
}
