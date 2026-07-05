package cn.ethan.ai.domain.agent.model;

import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.RunId;
import cn.ethan.ai.types.common.id.TurnId;

import java.util.Map;

/**
 * 售后Agent运行结果。
 */
public record AfterSalesRunResult(CaseId caseId,
                                  TurnId turnId,
                                  RunId runId,
                                  String stage,
                                  String checkpointId,
                                  String nextNode,
                                  String waitingReason,
                                  String terminalReason,
                                  String commandId,
                                  Map<String, Object> state) {

    public String caseIdValue() {
        return caseId == null ? null : caseId.value();
    }

    public String turnIdValue() {
        return turnId == null ? null : turnId.value();
    }

    public String runIdValue() {
        return runId == null ? null : runId.value();
    }

    public static AfterSalesRunResult of(String caseId,
                                         String turnId,
                                         String runId,
                                         String stage,
                                         String checkpointId,
                                         String nextNode,
                                         String waitingReason,
                                         String terminalReason,
                                         String commandId,
                                         Map<String, Object> state) {
        return new AfterSalesRunResult(
                CaseId.of(caseId),
                TurnId.of(turnId),
                RunId.of(runId),
                stage,
                checkpointId,
                nextNode,
                waitingReason,
                terminalReason,
                commandId,
                state
        );
    }
}
