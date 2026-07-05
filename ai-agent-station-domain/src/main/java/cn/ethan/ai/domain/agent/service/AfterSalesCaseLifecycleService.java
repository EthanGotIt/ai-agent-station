package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.exception.AfterSalesResumeConflictException;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentStateSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static cn.ethan.ai.types.common.util.Strings.isBlank;
import static cn.ethan.ai.types.common.util.Strings.nullToEmpty;

/**
 * 售后Case生命周期服务，负责 start/resume/cancel 编排。
 */
public final class AfterSalesCaseLifecycleService {

    private final IAfterSalesStateMachine stateMachine;
    private final IAfterSalesRepository repository;
    private final AfterSalesAuditService auditService;

    public AfterSalesCaseLifecycleService(IAfterSalesStateMachine stateMachine,
                                          IAfterSalesRepository repository,
                                          AfterSalesAuditService auditService) {
        this.stateMachine = stateMachine;
        this.repository = repository;
        this.auditService = auditService;
    }

    public AfterSalesRunResult start(AfterSalesRunCommand command) {
        validateStart(command);
        String caseId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();

        repository.createCase(caseId, command.userIdValue(), command.sessionIdValue(), command.message());
        auditService.beginExecution(caseId, turnId, runId, command.sessionIdValue(), command.userIdValue(), "START",
                command.message(), null, startedAt);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put(AfterSalesAgentState.CASE_ID, caseId);
        input.put(AfterSalesAgentState.TURN_ID, turnId);
        input.put(AfterSalesAgentState.RUN_ID, runId);
        input.put(AfterSalesAgentState.USER_ID, command.userIdValue());
        input.put(AfterSalesAgentState.SESSION_ID, command.sessionIdValue());
        input.put(AfterSalesAgentState.USER_MESSAGE, command.message());
        putIfText(input, AfterSalesAgentState.ORDER_ID, command.orderIdValue());
        putIfText(input, AfterSalesAgentState.REFUND_REASON, command.refundReason());

        try {
            AfterSalesAgentState state = stateMachine.execute(input, caseId);
            return persistAndMap(state, caseId, turnId, runId);
        } catch (RuntimeException error) {
            auditService.failExecution(turnId, runId, error);
            throw error;
        }
    }

    public AfterSalesRunResult resume(AfterSalesResumeCommand command, AfterSalesCaseView caseView) {
        validateResume(command);
        String caseId = caseView.caseIdValue();

        AfterSalesAgentStateSnapshot current = stateMachine.currentSnapshot(caseId)
                .orElseThrow(() -> new AfterSalesResumeConflictException("当前Case没有可恢复 checkpoint"));
        String currentCheckpointId = current.checkpointId() == null ? "" : current.checkpointId();
        if (!command.checkpointId().equals(currentCheckpointId)) {
            throw new AfterSalesResumeConflictException("checkpoint 已过期，当前 checkpointId=" + currentCheckpointId);
        }

        String turnId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        LocalDateTime startedAt = LocalDateTime.now();
        if (!repository.tryAcquireResume(caseId, currentCheckpointId, runId)) {
            throw new AfterSalesResumeConflictException("checkpoint 正在被其他Run恢复");
        }
        try {
            auditService.beginExecution(caseId, turnId, runId, caseView.sessionIdValue(), command.actorIdValue(),
                    command.action().name(), resumeInputSummary(command), currentCheckpointId, startedAt);
            Map<String, Object> update = resumeUpdate(current.state(), command);
            update.put(AfterSalesAgentState.TURN_ID, turnId);
            update.put(AfterSalesAgentState.RUN_ID, runId);
            AfterSalesAgentState state = stateMachine.resume(update, caseId, command.checkpointId());
            return persistAndMap(state, caseId, turnId, runId);
        } catch (RuntimeException error) {
            repository.releaseResume(caseId, runId);
            auditService.failExecution(turnId, runId, error);
            throw error;
        }
    }

    public boolean cancel(String caseId, String reason) {
        if (isBlank(caseId)) {
            return false;
        }
        Optional<AfterSalesCaseView> current = repository.findCase(caseId);
        if (current.isEmpty()) {
            return false;
        }
        String actualReason = isBlank(reason) ? "USER_CANCELLED" : reason;
        boolean cancelled = repository.cancelCase(caseId, actualReason);
        if (cancelled) {
            auditService.recordCancel(caseId, current.get().sessionIdValue(), current.get().userIdValue());
        }
        return cancelled;
    }

    private AfterSalesRunResult persistAndMap(AfterSalesAgentState state,
                                              String caseId,
                                              String turnId,
                                              String runId) {
        AfterSalesAgentStateSnapshot snapshot = stateMachine.currentSnapshot(caseId).orElse(null);
        String checkpointId = snapshot == null ? null : snapshot.checkpointId();
        String nextNode = snapshot == null ? null : snapshot.nextNode();
        AfterSalesCaseView view = AfterSalesCaseView.of(
                caseId,
                state.text(AfterSalesAgentState.USER_ID),
                state.text(AfterSalesAgentState.SESSION_ID),
                state.text(AfterSalesAgentState.ORDER_ID),
                state.stage().name(),
                checkpointId,
                nextNode,
                state.text(AfterSalesAgentState.TERMINAL_REASON),
                state.text(AfterSalesAgentState.COMMAND_ID)
        );
        repository.updateCase(view);
        auditService.completeExecution(state, turnId, runId, checkpointId);

        String waitingReason = switch (state.stage()) {
            case INTAKE -> state.flag(AfterSalesAgentState.NEED_USER_INPUT)
                    ? state.text(AfterSalesAgentState.DECISION_REASON)
                    : null;
            case PENDING_APPROVAL -> "REFUND_APPROVAL_REQUIRED";
            default -> null;
        };
        return AfterSalesRunResult.of(
                caseId,
                turnId,
                runId,
                state.stage().name(),
                checkpointId,
                nextNode,
                waitingReason,
                state.text(AfterSalesAgentState.TERMINAL_REASON),
                state.text(AfterSalesAgentState.COMMAND_ID),
                safePublicState(state)
        );
    }

    private Map<String, Object> resumeUpdate(AfterSalesAgentState state, AfterSalesResumeCommand command) {
        Map<String, Object> update = new LinkedHashMap<>();
        if (command.action() == AfterSalesResumeCommand.ResumeAction.SUPPLY_INFO) {
            if (state.stage() != AfterSalesStage.INTAKE || !state.flag(AfterSalesAgentState.NEED_USER_INPUT)) {
                throw new AfterSalesResumeConflictException("当前节点不接受补充信息");
            }
            if (isBlank(command.orderIdValue()) && isBlank(command.refundReason())) {
                throw new IllegalArgumentException("补充信息至少包含 orderId 或 refundReason");
            }
            putIfText(update, AfterSalesAgentState.ORDER_ID, command.orderIdValue());
            putIfText(update, AfterSalesAgentState.REFUND_REASON, command.refundReason());
            update.put(AfterSalesAgentState.NEED_USER_INPUT, false);
            update.put(AfterSalesAgentState.ERROR_TYPE, "");
            return update;
        }
        if (state.stage() != AfterSalesStage.PENDING_APPROVAL) {
            throw new AfterSalesResumeConflictException("当前节点不接受退款审批");
        }
        update.put(AfterSalesAgentState.APPROVAL_DECISION,
                command.action() == AfterSalesResumeCommand.ResumeAction.APPROVE ? "APPROVE" : "REJECT");
        return update;
    }

    private Map<String, Object> safePublicState(AfterSalesAgentState state) {
        Map<String, Object> safe = new LinkedHashMap<>();
        copyIfPresent(state, safe, AfterSalesAgentState.ORDER_ID);
        copyIfPresent(state, safe, AfterSalesAgentState.STAGE);
        copyIfPresent(state, safe, AfterSalesAgentState.ELIGIBLE);
        copyIfPresent(state, safe, AfterSalesAgentState.DECISION_REASON);
        copyIfPresent(state, safe, AfterSalesAgentState.TERMINAL_REASON);
        copyIfPresent(state, safe, AfterSalesAgentState.REPAIR_COUNT);
        copyIfPresent(state, safe, AfterSalesAgentState.RETRY_COUNT);
        copyIfPresent(state, safe, AfterSalesAgentState.RELOAD_COUNT);
        copyIfPresent(state, safe, AfterSalesAgentState.REPLAN_COUNT);
        copyIfPresent(state, safe, AfterSalesAgentState.CHECKLIST);
        return Map.copyOf(safe);
    }

    private void copyIfPresent(AfterSalesAgentState state, Map<String, Object> target, String key) {
        Object value = state.data().get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String resumeInputSummary(AfterSalesResumeCommand command) {
        return switch (command.action()) {
            case SUPPLY_INFO -> "orderId=" + nullToEmpty(command.orderIdValue())
                    + ",refundReason=" + nullToEmpty(command.refundReason());
            case APPROVE -> "APPROVE";
            case REJECT -> "REJECT";
        };
    }

    private void validateStart(AfterSalesRunCommand command) {
        if (command == null || isBlank(command.userIdValue()) || isBlank(command.sessionIdValue())) {
            throw new IllegalArgumentException("userId 和 sessionId 不能为空");
        }
        if (isBlank(command.message()) && isBlank(command.orderIdValue())) {
            throw new IllegalArgumentException("message 和 orderId 至少提供一个");
        }
    }

    private void validateResume(AfterSalesResumeCommand command) {
        if (command == null || isBlank(command.caseIdValue()) || isBlank(command.checkpointId())
                || command.action() == null || isBlank(command.actorIdValue())) {
            throw new IllegalArgumentException("caseId、checkpointId、action 和 actorId 不能为空");
        }
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value.trim());
        }
    }

}
