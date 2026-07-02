package cn.ethan.ai.domain.agent.model;

public record AfterSalesCaseView(String runId,
                                 String caseId,
                                 String userId,
                                 String sessionId,
                                 String orderId,
                                 String stage,
                                 String checkpointId,
                                 String nextNode,
                                 String terminalReason,
                                 String commandId) {
}
