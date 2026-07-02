package cn.ethan.ai.domain.agent.model;

import java.util.Map;

public record AfterSalesRunResult(String runId,
                                  String caseId,
                                  String stage,
                                  String checkpointId,
                                  String nextNode,
                                  String waitingReason,
                                  String terminalReason,
                                  String commandId,
                                  Map<String, Object> state) {
}
