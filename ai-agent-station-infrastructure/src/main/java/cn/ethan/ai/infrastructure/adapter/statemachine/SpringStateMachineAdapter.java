package cn.ethan.ai.infrastructure.adapter.statemachine;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentStateSnapshot;
import cn.ethan.ai.domain.agent.model.AfterSalesStateMachineResult;
import cn.ethan.ai.domain.agent.model.Checkpoint;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.domain.agent.port.driven.ICheckpointRepository;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.AfterSalesEvent;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.AfterSalesState;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.RefundInformationGatherer;
import cn.ethan.ai.infrastructure.observability.AfterSalesRuntimeMetrics;
import cn.ethan.ai.infrastructure.json.AfterSalesJsonCodec;
import cn.ethan.ai.types.common.id.CaseId;
import cn.ethan.ai.types.common.id.CheckpointId;
import cn.ethan.ai.types.common.id.TurnId;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.guard.Guard;
import org.springframework.statemachine.support.DefaultExtendedState;
import org.springframework.statemachine.support.DefaultStateMachineContext;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 基于 Spring State Machine 的售后退款状态机实现（Phase 7.4 Checkpoint 版）。
 *
 * <p>业务状态仅保留 INTAKE、PENDING_APPROVAL、COMPLETED、REJECTED；
 * 模型调用、资格判断等收敛到 INTAKE 阶段的信息收集动作中。
 * 状态机不再依赖内存缓存，每次执行或恢复都从 {@link Checkpoint} 重建。</p>
 */
public class SpringStateMachineAdapter implements IAfterSalesStateMachine {

    private static final String VAR_STATE = "afterSalesState";

    private final IAfterSalesRepository repository;
    private final ICheckpointRepository checkpointRepository;
    private final RefundInformationGatherer gatherer;

    public SpringStateMachineAdapter(IAfterSalesToolPort toolPort,
                                      IAfterSalesRepository repository,
                                      RefundPlanningAgent planningAgent,
                                      RefundInformationGatheringPolicy policy,
                                      TodoWriteTool todoWriteTool,
                                      ICheckpointRepository checkpointRepository) {
        this(toolPort, repository, planningAgent, policy, todoWriteTool,
                checkpointRepository, AfterSalesRuntimeMetrics.noop(), AfterSalesJsonCodec.defaultCodec());
    }

    public SpringStateMachineAdapter(IAfterSalesToolPort toolPort,
                                     IAfterSalesRepository repository,
                                     RefundPlanningAgent planningAgent,
                                     RefundInformationGatheringPolicy policy,
                                     TodoWriteTool todoWriteTool,
                                     ICheckpointRepository checkpointRepository,
                                     AfterSalesRuntimeMetrics metrics) {
        this(toolPort, repository, planningAgent, policy, todoWriteTool, checkpointRepository, metrics,
                AfterSalesJsonCodec.defaultCodec());
    }

    public SpringStateMachineAdapter(IAfterSalesToolPort toolPort,
                                     IAfterSalesRepository repository,
                                     RefundPlanningAgent planningAgent,
                                     RefundInformationGatheringPolicy policy,
                                     TodoWriteTool todoWriteTool,
                                     ICheckpointRepository checkpointRepository,
                                     AfterSalesRuntimeMetrics metrics,
                                     AfterSalesJsonCodec jsonCodec) {
        this.repository = repository;
        this.checkpointRepository = checkpointRepository;
        this.gatherer = new RefundInformationGatherer(
                toolPort, planningAgent, policy, todoWriteTool, checkpointRepository, metrics, jsonCodec);
    }

    @Override
    public AfterSalesStateMachineResult execute(Map<String, Object> input, String threadId) {
        StateMachine<AfterSalesState, AfterSalesEvent> machine = buildAndStartMachine();

        Map<String, Object> data = new LinkedHashMap<>(input);
        data.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());
        AfterSalesAgentState initial = new AfterSalesAgentState(data);
        machine.getExtendedState().getVariables().put(VAR_STATE, initial);

        CaseId caseId = extractCaseId(initial);
        TurnId turnId = extractTurnId(initial);

        AfterSalesAgentState gathered = gatherer.gather(initial, caseId, turnId);
        machine.getExtendedState().getVariables().put(VAR_STATE, gathered);
        sendEventAndWait(machine, AfterSalesEvent.ELIGIBLE);

        return boundaryResult(machine, caseId, turnId);
    }

    @Override
    public AfterSalesStateMachineResult resume(Map<String, Object> update, String threadId, String checkpointId) {
        CaseId caseId = CaseId.of(threadId);
        Checkpoint latest = checkpointRepository.findById(CheckpointId.of(checkpointId))
                .orElseThrow(() -> new IllegalStateException("没有可恢复的 Checkpoint，caseId=" + threadId));
        if (!latest.caseId().equals(caseId)) {
            throw new IllegalStateException("checkpoint 不属于当前 Case，caseId=" + threadId);
        }

        AfterSalesAgentState restored = merge(latest.state(), update);
        StateMachine<AfterSalesState, AfterSalesEvent> machine = buildAndRestoreMachine(latest.ssmState(), restored);

        switch (machine.getState().getId()) {
            case INTAKE -> {
                TurnId turnId = extractTurnId(restored);
                AfterSalesAgentState gathered = gatherer.gather(restored, caseId, turnId);
                machine.getExtendedState().getVariables().put(VAR_STATE, gathered);
                sendEventAndWait(machine, AfterSalesEvent.ELIGIBLE);
            }
            case PENDING_APPROVAL -> {
                String decision = restored.text(AfterSalesAgentState.APPROVAL_DECISION);
                AfterSalesEvent event = "APPROVE".equalsIgnoreCase(decision)
                        ? AfterSalesEvent.APPROVE
                        : AfterSalesEvent.REJECT;
                sendEventAndWait(machine, event);
            }
            case COMPLETED, REJECTED -> {
                // 终态无需继续驱动状态机
            }
        }

        return boundaryResult(machine, caseId, extractTurnId(restored));
    }

    @Override
    public Optional<AfterSalesAgentStateSnapshot> currentSnapshot(String threadId) {
        return repository.findCase(threadId)
                .map(caseView -> caseView.checkpointId())
                .filter(checkpointId -> checkpointId != null && !checkpointId.isBlank())
                .flatMap(checkpointId -> checkpointRepository.findById(CheckpointId.of(checkpointId)))
                .filter(checkpoint -> checkpoint.caseId().equals(CaseId.of(threadId)))
                .map(cp -> new AfterSalesAgentStateSnapshot(
                        cp.checkpointId().value(),
                        cp.ssmState(),
                        cp.state()));
    }

    private StateMachine<AfterSalesState, AfterSalesEvent> buildAndStartMachine() {
        StateMachine<AfterSalesState, AfterSalesEvent> machine = buildMachine();
        machine.startReactively().block();
        return machine;
    }

    private StateMachine<AfterSalesState, AfterSalesEvent> buildAndRestoreMachine(String ssmState,
                                                                                  AfterSalesAgentState state) {
        StateMachine<AfterSalesState, AfterSalesEvent> machine = buildMachine();
        AfterSalesState restoredState;
        try {
            restoredState = AfterSalesState.valueOf(ssmState);
        } catch (RuntimeException error) {
            throw new IllegalStateException("无法恢复状态机状态: " + ssmState, error);
        }
        DefaultExtendedState extendedState = new DefaultExtendedState();
        extendedState.getVariables().put(VAR_STATE, state);
        machine.getStateMachineAccessor().doWithAllRegions(access ->
                access.resetStateMachineReactively(new DefaultStateMachineContext<>(
                        restoredState, null, null, extendedState)).block());
        machine.startReactively().block();
        return machine;
    }

    private StateMachine<AfterSalesState, AfterSalesEvent> buildMachine() {
        try {
            StateMachineBuilder.Builder<AfterSalesState, AfterSalesEvent> builder = StateMachineBuilder.builder();

            builder.configureConfiguration()
                    .withConfiguration()
                    .autoStartup(false);

            builder.configureStates()
                    .withStates()
                    .initial(AfterSalesState.INTAKE)
                    .states(java.util.EnumSet.allOf(AfterSalesState.class))
                    .end(AfterSalesState.COMPLETED)
                    .end(AfterSalesState.REJECTED);

            builder.configureTransitions()
                    .withExternal()
                    .source(AfterSalesState.INTAKE).target(AfterSalesState.COMPLETED)
                    .event(AfterSalesEvent.ELIGIBLE)
                    .guard(completedGuard())

                    .and().withExternal()
                    .source(AfterSalesState.INTAKE).target(AfterSalesState.REJECTED)
                    .event(AfterSalesEvent.ELIGIBLE)
                    .guard(rejectedGuard())

                    .and().withExternal()
                    .source(AfterSalesState.INTAKE).target(AfterSalesState.PENDING_APPROVAL)
                    .event(AfterSalesEvent.ELIGIBLE)
                    .guard(eligibleGuard())

                    .and().withExternal()
                    .source(AfterSalesState.PENDING_APPROVAL).target(AfterSalesState.COMPLETED)
                    .event(AfterSalesEvent.APPROVE)
                    .action(executeRefundAction())

                    .and().withExternal()
                    .source(AfterSalesState.PENDING_APPROVAL).target(AfterSalesState.REJECTED)
                    .event(AfterSalesEvent.REJECT)
                    .action(rejectAction());

            return builder.build();
        } catch (Exception e) {
            throw new IllegalStateException("构建 Spring State Machine 失败", e);
        }
    }

    private Guard<AfterSalesState, AfterSalesEvent> eligibleGuard() {
        return context -> currentState(context).stage() == AfterSalesStage.PENDING_APPROVAL;
    }

    private Guard<AfterSalesState, AfterSalesEvent> completedGuard() {
        return context -> currentState(context).stage() == AfterSalesStage.COMPLETED;
    }

    private Guard<AfterSalesState, AfterSalesEvent> rejectedGuard() {
        return context -> currentState(context).stage() == AfterSalesStage.REJECTED;
    }

    private Action<AfterSalesState, AfterSalesEvent> executeRefundAction() {
        return context -> {
            AfterSalesAgentState state = currentState(context);
            String caseId = state.text(AfterSalesAgentState.CASE_ID);
            String idempotencyKey = caseId + ":REFUND";
            var result = repository.executeRefund(
                    caseId,
                    state.text(AfterSalesAgentState.ORDER_ID),
                    state.text(AfterSalesAgentState.USER_ID),
                    idempotencyKey
            );
            Map<String, Object> update = new LinkedHashMap<>();
            update.put(AfterSalesAgentState.STAGE, AfterSalesStage.COMPLETED.name());
            update.put(AfterSalesAgentState.TERMINAL_REASON,
                    result.success() ? "REFUND_VERIFIED" : result.reason());
            update.put(AfterSalesAgentState.COMMAND_ID, result.commandId());
            updateState(context, update);
        };
    }

    private Action<AfterSalesState, AfterSalesEvent> rejectAction() {
        return context -> updateState(context, Map.of(
                AfterSalesAgentState.STAGE, AfterSalesStage.REJECTED.name(),
                AfterSalesAgentState.TERMINAL_REASON, "APPROVAL_REJECTED"
        ));
    }

    private AfterSalesAgentState currentState(StateMachine<AfterSalesState, AfterSalesEvent> machine) {
        return (AfterSalesAgentState) machine.getExtendedState().getVariables().get(VAR_STATE);
    }

    private AfterSalesAgentState currentState(org.springframework.statemachine.StateContext<AfterSalesState, AfterSalesEvent> context) {
        return (AfterSalesAgentState) context.getExtendedState().getVariables().get(VAR_STATE);
    }

    private void updateState(org.springframework.statemachine.StateContext<AfterSalesState, AfterSalesEvent> context,
                             Map<String, Object> update) {
        AfterSalesAgentState current = currentState(context);
        Map<String, Object> merged = new LinkedHashMap<>(current.data());
        merged.putAll(update);
        context.getExtendedState().getVariables().put(VAR_STATE, new AfterSalesAgentState(merged));
    }

    private void sendEventAndWait(StateMachine<AfterSalesState, AfterSalesEvent> machine, AfterSalesEvent event) {
        machine.sendEvent(Mono.just(MessageBuilder.withPayload(event).build())).blockLast();
    }

    private AfterSalesStateMachineResult boundaryResult(StateMachine<AfterSalesState, AfterSalesEvent> machine,
                                                        CaseId caseId,
                                                        TurnId turnId) {
        AfterSalesAgentState state = currentState(machine);
        String ssmState = machine.getState().getId().name();
        Checkpoint checkpoint = new Checkpoint(
                CheckpointId.of(UUID.randomUUID().toString()),
                caseId,
                turnId,
                null,
                ssmState,
                state,
                state.stage(),
                LocalDateTime.now()
        );
        return new AfterSalesStateMachineResult(state, checkpoint, ssmState);
    }

    private static CaseId extractCaseId(AfterSalesAgentState state) {
        String value = state.text(AfterSalesAgentState.CASE_ID);
        return value == null || value.isBlank()
                ? CaseId.of(UUID.randomUUID().toString())
                : CaseId.of(value);
    }

    private static TurnId extractTurnId(AfterSalesAgentState state) {
        String value = state.text(AfterSalesAgentState.TURN_ID);
        return value == null || value.isBlank()
                ? TurnId.of(UUID.randomUUID().toString())
                : TurnId.of(value);
    }

    private static AfterSalesAgentState merge(AfterSalesAgentState state, Map<String, Object> update) {
        if (update == null || update.isEmpty()) {
            return state;
        }
        Map<String, Object> merged = new LinkedHashMap<>(state.data());
        merged.putAll(update);
        return new AfterSalesAgentState(merged);
    }
}
