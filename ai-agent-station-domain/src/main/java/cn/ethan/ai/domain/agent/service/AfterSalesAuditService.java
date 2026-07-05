package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatus;
import cn.ethan.ai.domain.agent.port.driven.IAgentRunRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static cn.ethan.ai.types.common.util.Strings.isBlank;

/**
 * 售后Agent审计服务，负责 turn/run/step 记录创建。
 */
public final class AfterSalesAuditService {

    private static final String AGENT_ID = "durable-after-sales";

    private final IAgentRunRepository runRepository;

    public AfterSalesAuditService(IAgentRunRepository runRepository) {
        this.runRepository = runRepository;
    }

    public void beginExecution(String caseId,
                               String turnId,
                               String runId,
                               String sessionId,
                               String actorId,
                               String triggerType,
                               String inputSummary,
                               String checkpointBefore,
                               LocalDateTime startedAt) {
        if (runRepository == null) {
            return;
        }
        runRepository.createTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .caseId(caseId)
                .sessionId(sessionId)
                .actorId(actorId)
                .turnType(triggerType)
                .inputSummary(inputSummary)
                .status(AgentRunStatus.RUNNING.name())
                .startTime(startedAt)
                .build());
        runRepository.createRun(AgentRunRecord.builder()
                .runId(runId)
                .turnId(turnId)
                .caseId(caseId)
                .agentId(AGENT_ID)
                .triggerType("START".equals(triggerType) ? "START" : "RESUME")
                .attemptNo(runRepository.nextAttemptNo(turnId))
                .status(AgentRunStatus.RUNNING)
                .checkpointBefore(checkpointBefore)
                .startTime(startedAt)
                .build());
    }

    public void completeExecution(AfterSalesAgentState state,
                                  String turnId,
                                  String runId,
                                  String checkpointId) {
        if (runRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String summary = state.stage().name() + optionalReason(state);
        runRepository.updateRun(AgentRunRecord.builder()
                .runId(runId)
                .status(AgentRunStatus.SUCCESS)
                .finalSummary(summary)
                .checkpointAfter(checkpointId)
                .endTime(now)
                .build());
        runRepository.completeTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .outputSummary(summary)
                .status(AgentRunStatus.SUCCESS.name())
                .endTime(now)
                .build());
    }

    public void failExecution(String turnId, String runId, RuntimeException error) {
        if (runRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        runRepository.updateRun(AgentRunRecord.builder()
                .runId(runId)
                .status(AgentRunStatus.FAILED)
                .errorMessage(message)
                .endTime(now)
                .build());
        runRepository.completeTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .outputSummary(message)
                .status(AgentRunStatus.FAILED.name())
                .endTime(now)
                .build());
    }

    public void recordCancel(String caseId, String sessionId, String userId) {
        if (runRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String turnId = UUID.randomUUID().toString();
        runRepository.createTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .caseId(caseId)
                .sessionId(sessionId)
                .actorId(userId)
                .turnType("CANCEL")
                .inputSummary("USER_CANCELLED")
                .outputSummary(AgentRunStatus.CANCELLED.name())
                .status(AgentRunStatus.CANCELLED.name())
                .startTime(now)
                .endTime(now)
                .build());
    }

    private String optionalReason(AfterSalesAgentState state) {
        String reason = state.text(AfterSalesAgentState.TERMINAL_REASON);
        if (isBlank(reason)) {
            reason = state.text(AfterSalesAgentState.DECISION_REASON);
        }
        return isBlank(reason) ? "" : ":" + reason;
    }
}
