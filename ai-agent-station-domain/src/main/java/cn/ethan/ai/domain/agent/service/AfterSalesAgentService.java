package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.adapter.repository.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesCaseView;
import cn.ethan.ai.domain.agent.model.AfterSalesResumeCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunCommand;
import cn.ethan.ai.domain.agent.model.AfterSalesRunResult;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatus;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatus;
import cn.ethan.ai.domain.agent.service.exception.AfterSalesResumeConflictException;
import org.bsc.langgraph4j.state.StateSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

public final class AfterSalesAgentService {

    private final AfterSalesGraphRuntime graphRuntime;
    private final IAfterSalesRepository repository;
    private final IAgentRunRepository runRepository;

    public AfterSalesAgentService(AfterSalesGraphRuntime graphRuntime, IAfterSalesRepository repository) {
        this(graphRuntime, repository, null);
    }

    public AfterSalesAgentService(AfterSalesGraphRuntime graphRuntime,
                                  IAfterSalesRepository repository,
                                  IAgentRunRepository runRepository) {
        this.graphRuntime = graphRuntime;
        this.repository = repository;
        this.runRepository = runRepository;
    }

    public AfterSalesRunResult start(AfterSalesRunCommand command) {
        validateStart(command);
        String runId = UUID.randomUUID().toString();
        String caseId = UUID.randomUUID().toString();
        repository.createCase(runId, caseId, command.userId(), command.sessionId(), command.message());
        if (runRepository != null) {
            runRepository.createRun(AgentRunRecord.builder()
                    .runId(runId)
                    .agentId("durable-after-sales")
                    .sessionId(command.sessionId())
                    .userMessage(command.message())
                    .status(AgentRunStatus.RUNNING)
                    .startTime(LocalDateTime.now())
                    .build());
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put(AfterSalesAgentState.RUN_ID, runId);
        input.put(AfterSalesAgentState.CASE_ID, caseId);
        input.put(AfterSalesAgentState.USER_ID, command.userId());
        input.put(AfterSalesAgentState.SESSION_ID, command.sessionId());
        input.put(AfterSalesAgentState.USER_MESSAGE, command.message());
        putIfText(input, AfterSalesAgentState.ORDER_ID, command.orderId());
        putIfText(input, AfterSalesAgentState.REFUND_REASON, command.refundReason());

        AfterSalesAgentState state = graphRuntime.execute(input, runId);
        return persistAndMap(state, runId, caseId);
    }

    public AfterSalesRunResult resume(AfterSalesResumeCommand command) {
        if (command == null
                || isBlank(command.runId())
                || isBlank(command.checkpointId())
                || command.action() == null) {
            throw new IllegalArgumentException("runId、checkpointId 和 action 不能为空");
        }
        AfterSalesCaseView caseView = repository.findCase(command.runId())
                .orElseThrow(() -> new IllegalArgumentException("售后运行不存在，runId=" + command.runId()));
        if (AfterSalesStage.COMPLETED.name().equals(caseView.stage())
                || AfterSalesStage.REJECTED.name().equals(caseView.stage())
                || AfterSalesStage.CANCELLED.name().equals(caseView.stage())) {
            throw new AfterSalesResumeConflictException("售后运行已结束，不能继续恢复");
        }

        StateSnapshot<AfterSalesAgentState> current = graphRuntime.currentSnapshot(command.runId())
                .orElseThrow(() -> new AfterSalesResumeConflictException("当前运行没有可恢复 checkpoint"));
        String currentCheckpointId = current.config().checkPointId().orElse("");
        if (!command.checkpointId().equals(currentCheckpointId)) {
            throw new AfterSalesResumeConflictException("checkpoint 已过期，当前 checkpointId=" + currentCheckpointId);
        }

        Map<String, Object> update = resumeUpdate(current.state(), command);
        AfterSalesAgentState state = graphRuntime.resume(update, command.runId(), command.checkpointId());
        return persistAndMap(state, command.runId(), caseView.caseId());
    }

    public Optional<AfterSalesCaseView> query(String runId) {
        return isBlank(runId) ? Optional.empty() : repository.findCase(runId);
    }

    public boolean cancel(String runId, String reason) {
        if (isBlank(runId)) {
            return false;
        }
        String actualReason = isBlank(reason) ? "USER_CANCELLED" : reason;
        boolean cancelled = repository.cancelCase(runId, actualReason);
        if (cancelled && runRepository != null) {
            runRepository.cancelRun(runId, actualReason);
        }
        return cancelled;
    }

    private Map<String, Object> resumeUpdate(AfterSalesAgentState state, AfterSalesResumeCommand command) {
        Map<String, Object> update = new LinkedHashMap<>();
        if (command.action() == AfterSalesResumeCommand.ResumeAction.SUPPLY_INFO) {
            if (state.stage() != AfterSalesStage.NEED_USER_INPUT) {
                throw new AfterSalesResumeConflictException("当前节点不接受补充信息");
            }
            if (isBlank(command.orderId()) && isBlank(command.refundReason())) {
                throw new IllegalArgumentException("补充信息至少包含 orderId 或 refundReason");
            }
            putIfText(update, AfterSalesAgentState.ORDER_ID, command.orderId());
            putIfText(update, AfterSalesAgentState.REFUND_REASON, command.refundReason());
            update.put(AfterSalesAgentState.ERROR_TYPE, "");
            update.put(AfterSalesAgentState.ROUTE, AfterSalesGraphRuntime.ROUTE_REQUEST);
            return update;
        }
        if (state.stage() != AfterSalesStage.READY_FOR_APPROVAL) {
            throw new AfterSalesResumeConflictException("当前节点不接受退款审批");
        }
        update.put(AfterSalesAgentState.APPROVAL_DECISION,
                command.action() == AfterSalesResumeCommand.ResumeAction.APPROVE ? "APPROVE" : "REJECT");
        return update;
    }

    private AfterSalesRunResult persistAndMap(AfterSalesAgentState state, String runId, String caseId) {
        StateSnapshot<AfterSalesAgentState> snapshot = graphRuntime.currentSnapshot(runId).orElse(null);
        String checkpointId = snapshot == null ? null : snapshot.config().checkPointId().orElse(null);
        String nextNode = snapshot == null ? null : snapshot.next();
        AfterSalesCaseView view = new AfterSalesCaseView(
                runId,
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
        persistRunAndStep(state, view);
        String waitingReason = switch (state.stage()) {
            case NEED_USER_INPUT -> state.text(AfterSalesAgentState.DECISION_REASON);
            case READY_FOR_APPROVAL -> "REFUND_APPROVAL_REQUIRED";
            default -> null;
        };
        return new AfterSalesRunResult(
                runId,
                caseId,
                state.stage().name(),
                checkpointId,
                nextNode,
                waitingReason,
                state.text(AfterSalesAgentState.TERMINAL_REASON),
                state.text(AfterSalesAgentState.COMMAND_ID),
                safePublicState(state)
        );
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
        return Map.copyOf(safe);
    }

    private void copyIfPresent(AfterSalesAgentState state, Map<String, Object> target, String key) {
        Object value = state.data().get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private void persistRunAndStep(AfterSalesAgentState state, AfterSalesCaseView view) {
        if (runRepository == null) {
            return;
        }
        AgentRunStatus runStatus = switch (state.stage()) {
            case COMPLETED -> AgentRunStatus.SUCCESS;
            case REJECTED -> AgentRunStatus.FAILED;
            case CANCELLED -> AgentRunStatus.CANCELLED;
            default -> AgentRunStatus.RUNNING;
        };
        LocalDateTime now = LocalDateTime.now();
        runRepository.updateRun(AgentRunRecord.builder()
                .runId(view.runId())
                .status(runStatus)
                .finalSummary(runStatus == AgentRunStatus.SUCCESS
                        ? state.text(AfterSalesAgentState.TERMINAL_REASON) : null)
                .errorMessage(runStatus == AgentRunStatus.FAILED
                        ? state.text(AfterSalesAgentState.TERMINAL_REASON) : null)
                .endTime(runStatus == AgentRunStatus.RUNNING ? null : now)
                .build());
        String stepId = isBlank(view.checkpointId()) ? UUID.randomUUID().toString() : view.checkpointId();
        runRepository.createStep(AgentStepRunRecord.builder()
                .runId(view.runId())
                .stepId(stepId)
                .stepName(state.stage().name())
                .stepOrder(graphRuntime.historySize(view.runId()))
                .stepType("AFTER_SALES_GRAPH")
                .status(runStatus == AgentRunStatus.FAILED
                        ? AgentStepRunStatus.FAILED : AgentStepRunStatus.SUCCESS)
                .outputSummary(state.text(AfterSalesAgentState.DECISION_REASON))
                .errorMessage(runStatus == AgentRunStatus.FAILED
                        ? state.text(AfterSalesAgentState.TERMINAL_REASON) : null)
                .costMillis(0L)
                .startTime(now)
                .endTime(now)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    private void validateStart(AfterSalesRunCommand command) {
        if (command == null || isBlank(command.userId()) || isBlank(command.sessionId())) {
            throw new IllegalArgumentException("userId 和 sessionId 不能为空");
        }
        if (isBlank(command.message()) && isBlank(command.orderId())) {
            throw new IllegalArgumentException("message 和 orderId 至少提供一个");
        }
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (!isBlank(value)) {
            target.put(key, value.trim());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
