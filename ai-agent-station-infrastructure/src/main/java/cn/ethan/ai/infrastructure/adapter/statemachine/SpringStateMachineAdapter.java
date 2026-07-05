package cn.ethan.ai.infrastructure.adapter.statemachine;

import cn.ethan.ai.domain.agent.model.AfterSalesAgentState;
import cn.ethan.ai.domain.agent.model.AfterSalesAgentStateSnapshot;
import cn.ethan.ai.domain.agent.model.valobj.enums.AfterSalesStage;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesRepository;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesStateMachine;
import cn.ethan.ai.domain.agent.policy.RefundInformationGatheringPolicy;
import cn.ethan.ai.domain.agent.port.driven.IAfterSalesToolPort;
import cn.ethan.ai.infrastructure.adapter.ai.RefundPlanningAgent;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.AfterSalesEvent;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.AfterSalesState;
import cn.ethan.ai.infrastructure.adapter.statemachine.ssm.RefundInformationGatherer;
import org.springaicommunity.agent.tools.TodoWriteTool;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.statemachine.StateMachine;
import org.springframework.statemachine.action.Action;
import org.springframework.statemachine.config.StateMachineBuilder;
import org.springframework.statemachine.guard.Guard;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Spring State Machine 的售后退款状态机实现（Phase 7.1 三态精简版）。
 *
 * <p>业务状态仅保留 INTAKE、PENDING_APPROVAL、COMPLETED、REJECTED；
 * 模型调用、资格判断等收敛到 INTAKE 阶段的信息收集动作中。</p>
 */
public class SpringStateMachineAdapter implements IAfterSalesStateMachine {

    private static final String VAR_STATE = "afterSalesState";
    private static final String VAR_CHECKPOINT = "checkpointId";

    private final IAfterSalesToolPort toolPort;
    private final IAfterSalesRepository repository;
    private final RefundInformationGatherer gatherer;
    private final ConcurrentHashMap<String, StateMachine<AfterSalesState, AfterSalesEvent>> machines = new ConcurrentHashMap<>();

    public SpringStateMachineAdapter(IAfterSalesToolPort toolPort,
                                      IAfterSalesRepository repository,
                                      RefundPlanningAgent planningAgent,
                                      RefundInformationGatheringPolicy policy,
                                      TodoWriteTool todoWriteTool) {
        this.toolPort = toolPort;
        this.repository = repository;
        this.gatherer = new RefundInformationGatherer(toolPort, planningAgent, policy, todoWriteTool);
    }

    @Override
    public AfterSalesAgentState execute(Map<String, Object> input, String threadId) {
        StateMachine<AfterSalesState, AfterSalesEvent> machine = buildMachine();
        machines.put(threadId, machine);

        Map<String, Object> data = new LinkedHashMap<>(input);
        data.put(AfterSalesAgentState.STAGE, AfterSalesStage.INTAKE.name());
        machine.getExtendedState().getVariables().put(VAR_STATE, new AfterSalesAgentState(data));
        machine.getExtendedState().getVariables().put(VAR_CHECKPOINT, UUID.randomUUID().toString());

        machine.startReactively().block();

        AfterSalesAgentState gathered = gatherer.gather(currentState(machine));
        machine.getExtendedState().getVariables().put(VAR_STATE, gathered);
        sendEventAndWait(machine, AfterSalesEvent.ELIGIBLE);

        return currentState(machine);
    }

    @Override
    public AfterSalesAgentState resume(Map<String, Object> update, String threadId, String checkpointId) {
        StateMachine<AfterSalesState, AfterSalesEvent> machine = machines.get(threadId);
        if (machine == null) {
            throw new IllegalStateException("没有可恢复的 StateMachine，threadId=" + threadId);
        }

        AfterSalesAgentState current = currentState(machine);
        Map<String, Object> merged = new LinkedHashMap<>(current.data());
        if (update != null) {
            merged.putAll(update);
        }
        machine.getExtendedState().getVariables().put(VAR_STATE, new AfterSalesAgentState(merged));
        machine.getExtendedState().getVariables().put(VAR_CHECKPOINT, UUID.randomUUID().toString());

        AfterSalesState ssmState = machine.getState().getId();
        if (ssmState == AfterSalesState.INTAKE) {
            AfterSalesAgentState gathered = gatherer.gather(currentState(machine));
            machine.getExtendedState().getVariables().put(VAR_STATE, gathered);
            sendEventAndWait(machine, AfterSalesEvent.ELIGIBLE);
        } else if (ssmState == AfterSalesState.PENDING_APPROVAL) {
            String decision = currentState(machine).text(AfterSalesAgentState.APPROVAL_DECISION);
            AfterSalesEvent event = "APPROVE".equalsIgnoreCase(decision)
                    ? AfterSalesEvent.APPROVE
                    : AfterSalesEvent.REJECT;
            sendEventAndWait(machine, event);
        } else {
            throw new IllegalStateException("当前 SSM 状态不接受恢复：" + ssmState);
        }

        return currentState(machine);
    }

    @Override
    public Optional<AfterSalesAgentStateSnapshot> currentSnapshot(String threadId) {
        StateMachine<AfterSalesState, AfterSalesEvent> machine = machines.get(threadId);
        if (machine == null) {
            return Optional.empty();
        }
        AfterSalesState state = machine.getState().getId();
        String checkpointId = (String) machine.getExtendedState().getVariables().get(VAR_CHECKPOINT);
        AfterSalesAgentState agentState = currentState(machine);
        return Optional.of(new AfterSalesAgentStateSnapshot(checkpointId, state.name(), agentState));
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
}
