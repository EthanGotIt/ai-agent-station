package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentTurnStatus;
import cn.ethan.ai.domain.agent.port.driven.IAgentTurnRepository;

import java.time.LocalDateTime;
import java.util.UUID;

import static cn.ethan.ai.types.common.util.Strings.isBlank;

/**
 * 售后Agent审计服务，负责 Turn 记录创建与完成。
 *
 * <p>Run 已合并进 Turn，一次外部交互即一次执行尝试。</p>
 */
public final class AfterSalesAuditService {

    private final IAgentTurnRepository turnRepository;

    public AfterSalesAuditService(IAgentTurnRepository turnRepository) {
        this.turnRepository = turnRepository;
    }

    public void beginExecution(String caseId,
                                String turnId,
                                String sessionId,
                                String actorId,
                                String turnType,
                                String inputSummary,
                                String checkpointBefore,
                                LocalDateTime startedAt) {
        if (turnRepository == null) {
            return;
        }
        turnRepository.createTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .caseId(caseId)
                .sessionId(sessionId)
                .actorId(actorId)
                .turnType(turnType)
                .attemptNo(turnRepository.nextAttemptNo(caseId))
                .inputSummary(inputSummary)
                .status(AgentTurnStatus.RUNNING.name())
                .checkpointBefore(checkpointBefore)
                .startTime(startedAt)
                .build());
    }

    public void completeExecution(AfterSalesAgentState state,
                                   String turnId,
                                   String checkpointId) {
        if (turnRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String summary = state.stage().name() + optionalReason(state);
        turnRepository.completeTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .outputSummary(summary)
                .status(AgentTurnStatus.SUCCESS.name())
                .checkpointAfter(checkpointId)
                .endTime(now)
                .build());
    }

    public void failExecution(String turnId, RuntimeException error) {
        if (turnRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        turnRepository.completeTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .outputSummary(message)
                .status(AgentTurnStatus.FAILED.name())
                .endTime(now)
                .build());
    }

    public void recordCancel(String caseId, String sessionId, String userId) {
        if (turnRepository == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String turnId = UUID.randomUUID().toString();
        turnRepository.createTurn(AgentTurnRecord.builder()
                .turnId(turnId)
                .caseId(caseId)
                .sessionId(sessionId)
                .actorId(userId)
                .turnType("CANCEL")
                .inputSummary("USER_CANCELLED")
                .outputSummary(AgentTurnStatus.CANCELLED.name())
                .status(AgentTurnStatus.CANCELLED.name())
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
