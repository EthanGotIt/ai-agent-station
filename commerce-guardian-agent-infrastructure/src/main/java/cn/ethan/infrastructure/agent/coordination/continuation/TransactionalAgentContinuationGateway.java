package cn.ethan.infrastructure.agent.coordination.continuation;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.coordination.AgentContinuationGateway;
import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.coordination.AgentDecisionTypeEnum;
import cn.ethan.core.agent.execution.AgentTurnItemPayloads;
import cn.ethan.core.agent.execution.AgentTurnQueue;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 类型职责：在一次本地事务中 admission 外部动作结果触发的唯一 Continuation Turn。
 *
 * <p>该适配器是 Workflow 和 Worker 共用的唯一入口。它不调用模型，也不改变外部动作；
 * 只从业务事实推导根 Turn、父 Turn、cycle 和幂等键，持久化首个 Item，并在提交后入队。</p>
 *
 * @author ethan
 * @date 2026-08-27
 */
@Component
public final class TransactionalAgentContinuationGateway implements AgentContinuationGateway {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalAgentContinuationGateway.class);
    private final AgentTurnStore turns;
    private final AgentItemStore items;
    private final AgentTurnQueue queue;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final boolean enabled;
    private final int maxAgentCycles;

    /** 生产装配边界：Continuation 共享 Runtime FIFO，提交后才触发内存入队。 */
    @Autowired
    public TransactionalAgentContinuationGateway(
            AgentTurnStore turns,
            AgentItemStore items,
            AgentTurnQueue queue,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${ai-agent.runtime.continuation-enabled:true}") boolean enabled,
            @Value("${ai-agent.runtime.max-agent-cycles:3}") int maxAgentCycles
    ) {
        this(turns, items, queue, transactionManager, clock, enabled, maxAgentCycles, true);
    }

    /** 测试边界：允许使用内存 Store 和显式 Clock，不依赖 Spring 事务。 */
    public TransactionalAgentContinuationGateway(
            AgentTurnStore turns,
            AgentItemStore items,
            AgentTurnQueue queue,
            Clock clock,
            boolean enabled,
            int maxAgentCycles
    ) {
        this(turns, items, queue, null, clock, enabled, maxAgentCycles, false);
    }

    private TransactionalAgentContinuationGateway(
            AgentTurnStore turns,
            AgentItemStore items,
            AgentTurnQueue queue,
            PlatformTransactionManager transactionManager,
            Clock clock,
            boolean enabled,
            int maxAgentCycles,
            boolean productionConstructor
    ) {
        this.turns = turns;
        this.items = items;
        this.queue = queue;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.enabled = enabled;
        this.maxAgentCycles = Math.max(1, Math.min(maxAgentCycles, 5));
    }

    @Override
    public AdmissionResult admit(ExternalActionCommandModel command, AgentItemModel triggerItem) {
        if (!enabled || command == null || triggerItem == null
                || !isContinuationStatus(command.status()) || turns == null || items == null) {
            return AdmissionResult.none();
        }
        Instant now = clock.instant();
        if (transactionTemplate == null) {
            return admitInTransaction(command, triggerItem, now);
        }
        AdmissionResult result = transactionTemplate.execute(status -> admitInTransaction(command, triggerItem, now));
        return result == null ? AdmissionResult.none() : result;
    }

    private AdmissionResult admitInTransaction(
            ExternalActionCommandModel command,
            AgentItemModel triggerItem,
            Instant now
    ) {
        AgentTurnModel parent = turns.findTurn(command.userId(), command.turnId()).orElse(null);
        if (parent == null || !parent.threadId().equals(command.threadId())
                || !triggerItem.threadId().equals(command.threadId())) {
            return AdmissionResult.none();
        }
        int cycleNo = parent.continuationInput() == null
                ? 1 : parent.continuationInput().cycleNo() + 1;
        if (cycleNo > maxAgentCycles) {
            return stopLimit(parent, command, cycleNo, now);
        }

        AgentContinuationInput input = new AgentContinuationInput(
                rootTurnId(parent), parent.turnId(), command.runId(), command.commandId(),
                command.status().name(), Math.max(0L, triggerItem.sequence()), cycleNo);
        String requestId = input.idempotencyKey();
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequestForUpdate(command.userId(), requestId);
        if (duplicate.isPresent()) {
            return new AdmissionResult(duplicate.get(), List.of(), false, false, cycleNo);
        }

        AgentTurnModel continuation = new AgentTurnModel(
                UUID.randomUUID().toString(), command.threadId(), command.userId(), requestId,
                "根据已完成的订单 Workflow 继续处理原目标", AgentTurnStatusEnum.QUEUED, 0,
                null, null, now, null, null, null, 0L,
                AgentTurnInputKindEnum.AGENT_CONTINUATION, null, input);
        AgentItemModel initial = new AgentItemModel(
                UUID.randomUUID().toString(), command.threadId(), continuation.turnId(), 0,
                AgentItemTypeEnum.AGENT_CONTINUATION, AgentTurnItemPayloads.continuation(input), now);
        try {
            long sequence = turns.createTurnWithInitialItem(continuation, initial);
            AgentItemModel persistedInitial = sequence > 0
                    ? AgentTurnItemPayloads.withSequence(initial, sequence)
                    : append(initial);
            AgentItemModel queuedState = append(new AgentItemModel(
                    UUID.randomUUID().toString(), command.threadId(), continuation.turnId(), 0,
                    AgentItemTypeEnum.TURN_STATE,
                    AgentTurnItemPayloads.turnState(AgentTurnStatusEnum.QUEUED, null), now));
            enqueueAfterCommit(continuation);
            return new AdmissionResult(continuation, List.of(persistedInitial, queuedState), true, false, cycleNo);
        } catch (RuntimeException creationFailure) {
            Optional<AgentTurnModel> raced = turns.findTurnByRequestForUpdate(command.userId(), requestId);
            if (raced.isPresent()) {
                return new AdmissionResult(raced.get(), List.of(), false, false, cycleNo);
            }
            throw creationFailure;
        }
    }

    private AdmissionResult stopLimit(
            AgentTurnModel parent,
            ExternalActionCommandModel command,
            int cycleNo,
            Instant now
    ) {
        AgentItemModel decision = append(new AgentItemModel(
                UUID.randomUUID().toString(), parent.threadId(), parent.turnId(), 0,
                AgentItemTypeEnum.AGENT_DECISION,
                AgentTurnItemPayloads.decision(AgentDecisionTypeEnum.STOP_LIMIT,
                        maxAgentCycles, command.runId(), "MAX_AGENT_CYCLES"), now));
        AgentItemModel message = append(new AgentItemModel(
                UUID.randomUUID().toString(), parent.threadId(), parent.turnId(), 0,
                AgentItemTypeEnum.ASSISTANT_MESSAGE,
                "已达到本次订单处理的最大自动决策轮次，请继续使用查询或人工操作。", now));
        return AdmissionResult.stopLimit(List.of(decision, message), cycleNo);
    }

    private String rootTurnId(AgentTurnModel parent) {
        if (parent.continuationInput() != null) {
            return parent.continuationInput().rootTurnId();
        }
        if (parent.orderActionInput() != null) {
            return parent.orderActionInput().sourceTurnId();
        }
        return parent.turnId();
    }

    private AgentItemModel append(AgentItemModel item) {
        long sequence = items.appendItem(item);
        return AgentTurnItemPayloads.withSequence(item, sequence);
    }

    private void enqueueAfterCommit(AgentTurnModel turn) {
        if (queue == null || turn == null) {
            return;
        }
        afterCommit(() -> {
            try {
                queue.enqueuePersisted(turn);
            } catch (RuntimeException failure) {
                // Turn 已持久化；恢复扫描会再次发现 QUEUED，不能因队列暂满重做外部动作。
                LOGGER.warn("Agent Continuation 已持久化但暂未入队，turnId={}, errorType={}",
                        turn.turnId(), failure.getClass().getSimpleName());
            }
        });
    }

    private void afterCommit(Runnable callback) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    callback.run();
                }
            });
        } else {
            callback.run();
        }
    }

    private boolean isContinuationStatus(ExternalActionStatusEnum status) {
        return status == ExternalActionStatusEnum.SUCCEEDED
                || status == ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED;
    }
}
