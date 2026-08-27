package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.coordination.AgentDecisionTypeEnum;
import cn.ethan.core.agent.coordination.AgentOrderActionCoordinator;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.coordination.AgentOrderActionTypeEnum;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowOwnerRecoveryCandidate;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 类型职责：以 Thread 为队列键，负责 Turn 生命周期、持久化 Item 和可恢复 HITL。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class AgentTurnRuntimeService implements AgentTurnQueue {

    private static final String SAFE_EXECUTION_ERROR = "Agent 执行失败";

    private final AgentThreadStore threadStore;
    private final AgentTurnStore turns;
    private final AgentItemStore items;
    private final AgentQuestionCardStore questionCards;
    private final AgentWorkflowCheckpointStore checkpoints;
    private final AgentThreadService threads;
    private final AgentContextAssembler contextAssembler;
    private final AgentTurnExecutionRouter executionRouter;
    private final AgentThreadEventGateway events;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final Clock clock;
    private final int maxPendingPerThread;
    private final int maxPendingGlobal;
    private final Duration waitTimeout;
    private final Duration turnTimeout;
    private final int toolResultMaxCharacters;
    private final AgentRuntimeMetrics metrics;
    private final boolean continuationEnabled;
    private final int maxAgentCycles;
    private final AtomicInteger pendingGlobal = new AtomicInteger();
    private final Map<String, ThreadSlot> slots = new ConcurrentHashMap<>();

    public AgentTurnRuntimeService(
            AgentThreadStore threadStore,
            AgentTurnStore turns,
            AgentItemStore items,
            AgentThreadService threads,
            AgentContextAssembler contextAssembler,
            AgentTurnCoordinator coordinator,
            AgentThreadEventGateway events,
            Executor executor,
            ScheduledExecutorService scheduler,
            Clock clock,
            int maxPendingPerThread,
            int maxPendingGlobal,
            Duration waitTimeout,
            Duration turnTimeout,
            int toolResultMaxCharacters
    ) {
        this(threadStore, turns, items, threads, contextAssembler, coordinator, events, executor, scheduler, clock,
                maxPendingPerThread, maxPendingGlobal, waitTimeout, turnTimeout, toolResultMaxCharacters,
                AgentRuntimeMetrics.noop(), null, true, 3, null, null);
    }

    /** 生产装配边界：Runtime 只依赖新的 QuestionCard 与 Workflow Checkpoint 事实。 */
    public AgentTurnRuntimeService(
            AgentThreadStore threadStore,
            AgentTurnStore turns,
            AgentItemStore items,
            AgentThreadService threads,
            AgentContextAssembler contextAssembler,
            AgentTurnCoordinator coordinator,
            AgentThreadEventGateway events,
            Executor executor,
            ScheduledExecutorService scheduler,
            Clock clock,
            int maxPendingPerThread,
            int maxPendingGlobal,
            Duration waitTimeout,
            Duration turnTimeout,
            int toolResultMaxCharacters,
            AgentRuntimeMetrics metrics,
            AgentOrderActionCoordinator orderActionCoordinator,
            boolean continuationEnabled,
            int maxAgentCycles,
            AgentQuestionCardStore questionCards,
            AgentWorkflowCheckpointStore checkpoints
    ) {
        this.threadStore = threadStore;
        this.turns = turns;
        this.items = items;
        this.questionCards = questionCards;
        this.checkpoints = checkpoints;
        this.threads = threads;
        this.contextAssembler = contextAssembler;
        this.executionRouter = new AgentTurnExecutionRouter(coordinator, orderActionCoordinator);
        this.events = events;
        this.executor = executor;
        this.scheduler = scheduler;
        this.clock = clock;
        this.maxPendingPerThread = Math.max(1, maxPendingPerThread);
        this.maxPendingGlobal = Math.max(this.maxPendingPerThread, maxPendingGlobal);
        this.waitTimeout = waitTimeout == null ? Duration.ofMinutes(2) : waitTimeout;
        this.turnTimeout = turnTimeout == null ? Duration.ofMinutes(4) : turnTimeout;
        this.toolResultMaxCharacters = Math.max(256, toolResultMaxCharacters);
        this.metrics = metrics == null ? AgentRuntimeMetrics.noop() : metrics;
        this.continuationEnabled = continuationEnabled;
        this.maxAgentCycles = Math.max(1, Math.min(maxAgentCycles, 5));
    }

    public void recoverPersistedTurns() {
        for (AgentWorkflowOwnerRecoveryCandidate candidate : turns.listWorkflowOwnerRecoveryCandidates()) {
            reconcileWorkflowOwner(candidate);
        }
        for (AgentTurnModel persisted : turns.listRecoverableTurns()) {
            AgentThreadModel thread = threadStore.findThread(persisted.userId(), persisted.threadId()).orElse(null);
            if (thread == null) {
                finish(persisted, AgentTurnStatusEnum.FAILED, "THREAD_NOT_FOUND");
                continue;
            }
            ThreadSlot slot = slots.computeIfAbsent(thread.threadId(), ignored -> new ThreadSlot());
            synchronized (slot) {
                if (persisted.status() == AgentTurnStatusEnum.ACTIVE) {
                    finish(persisted, AgentTurnStatusEnum.FAILED, "RUNTIME_RESTARTED");
                    continue;
                }
                if (persisted.questionAnswerInput() != null) {
                    if (!persisted.createdAt().plus(waitTimeout).isAfter(clock.instant())) {
                        finish(persisted, AgentTurnStatusEnum.TIMED_OUT, "QUEUE_WAIT_TIMEOUT");
                        continue;
                    }
                    try {
                        if (!matchesEnqueuedQuestionCard(persisted)) {
                            finish(persisted, AgentTurnStatusEnum.FAILED, "QUESTION_ANSWER_STALE");
                            continue;
                        }
                    } catch (RuntimeException questionFailure) {
                        metrics.observeFailure("QUESTION_ANSWER_RECOVERY_QUERY_FAILED");
                        finish(persisted, AgentTurnStatusEnum.FAILED, "QUESTION_ANSWER_RECOVERY_FAILED");
                        continue;
                    }
                }
                boolean present = (slot.active != null
                        && slot.active.turn.turnId().equals(persisted.turnId()))
                        || slot.queue.stream().anyMatch(queued -> queued.turn.turnId().equals(persisted.turnId()))
                        || slot.deferred.stream().anyMatch(queued -> queued.turn.turnId().equals(persisted.turnId()));
                if (present) {
                    continue;
                }
                if (slot.queue.size() + slot.deferred.size() >= maxPendingPerThread
                        || pendingGlobal.get() >= maxPendingGlobal) {
                    finish(persisted, AgentTurnStatusEnum.FAILED, "RUNTIME_QUEUE_OVERFLOW");
                    continue;
                }
                QueuedTurn queued = new QueuedTurn(
                        persisted, persisted.questionAnswerInput(),
                        new AtomicBoolean(false),
                        new AtomicBoolean(false), new AtomicReference<>());
                slot.queue.addLast(queued);
                pendingGlobal.incrementAndGet();
                if (!queued.continuation()) {
                    scheduleQueueTimeout(thread.threadId(), queued);
                }
                schedule(slot, thread);
            }
        }
    }

    /** 返回 Thread 上任意一种开放交互；同一 Thread 同时只能存在一个开放事实。 */
    private boolean hasOpenInteraction(String userId, String threadId) {
        if (questionCards != null && questionCards.findOpen(userId, threadId).isPresent()) {
            return true;
        }
        return checkpoints != null && checkpoints.findOpen(userId, threadId).isPresent();
    }

    private boolean hasBlockingInteraction(AgentTurnModel turn) {
        if (!hasOpenInteraction(turn.userId(), turn.threadId())) {
            return false;
        }
        if (turn.questionAnswerInput() != null && questionCards != null) {
            return questionCards.findOpen(turn.userId(), turn.threadId())
                    .map(question -> !question.questionId().equals(turn.questionAnswerInput().questionId()))
                    .orElse(false);
        }
        return true;
    }

    /**
     * 将已在本地事务中提交的 continuation Turn 放入运行时队列。
     *
     * <p>该方法只接受持久化后的 QUEUED Turn；重复调用只会命中内存去重，
     * 因此可安全作为事务提交回调和启动恢复的共同入口。</p>
     */
    @Override
    public void enqueuePersisted(AgentTurnModel persisted) {
        if (persisted == null || persisted.status() != AgentTurnStatusEnum.QUEUED) {
            return;
        }
        AgentTurnModel latest = turns.findTurn(persisted.userId(), persisted.turnId()).orElse(null);
        if (latest == null || latest.status() != AgentTurnStatusEnum.QUEUED) {
            return;
        }
        AgentTurnModel current = latest;
        AgentThreadModel thread = threadStore.findThread(current.userId(), current.threadId()).orElse(null);
        if (thread == null) {
            finish(current, AgentTurnStatusEnum.FAILED, "THREAD_NOT_FOUND");
            return;
        }
        ThreadSlot slot = slots.computeIfAbsent(thread.threadId(), ignored -> new ThreadSlot());
        synchronized (slot) {
            if (slot.active != null && slot.active.turn.turnId().equals(current.turnId())) {
                return;
            }
            if (slot.queue.stream().anyMatch(queued -> queued.turn.turnId().equals(current.turnId()))
                    || slot.deferred.stream().anyMatch(queued -> queued.turn.turnId().equals(current.turnId()))) {
                return;
            }
            if (slot.queue.size() + slot.deferred.size() >= maxPendingPerThread
                    || pendingGlobal.get() >= maxPendingGlobal) {
                retryEnqueue(current);
                return;
            }
            QueuedTurn queued = new QueuedTurn(
                    current, current.questionAnswerInput(),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false), new AtomicReference<>());
            slot.queue.addLast(queued);
            pendingGlobal.incrementAndGet();
            if (!queued.continuation()) {
                scheduleQueueTimeout(thread.threadId(), queued);
            }
            schedule(slot, thread);
        }
    }

    private void retryEnqueue(AgentTurnModel persisted) {
        try {
            scheduler.schedule(() -> {
                try {
                    turns.findTurn(persisted.userId(), persisted.turnId())
                            .filter(current -> current.status() == AgentTurnStatusEnum.QUEUED)
                            .ifPresent(this::enqueuePersisted);
                } catch (RuntimeException refreshFailure) {
                    metrics.observeFailure("CONTINUATION_ENQUEUE_RETRY_REFRESH_FAILED");
                }
            }, 250L, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            metrics.observeFailure("CONTINUATION_ENQUEUE_RETRY_FAILED");
        }
    }

    private void reconcileWorkflowOwner(AgentWorkflowOwnerRecoveryCandidate candidate) {
        AgentTurnModel owner = candidate.turn();
        if (candidate.hasOpenInteraction()
                && candidate.workflowStatus() == AgentWorkflowStatusEnum.WAITING_USER_INPUT) {
            return;
        }
        switch (candidate.workflowStatus()) {
            case WAITING_EXTERNAL_ACTION -> {
                AgentTurnModel waiting = owner.workflow(owner.workflowRunId(),
                        AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION);
                if (updateTurn(owner, waiting)) {
                    appendItem(waiting, AgentItemTypeEnum.TURN_STATE,
                            AgentTurnItemPayloads.turnState(waiting.status(), "WORKFLOW_RECOVERED"));
                }
            }
            case COMPLETED -> finish(owner, AgentTurnStatusEnum.COMPLETED, "WORKFLOW_RECOVERED");
            case REJECTED -> finish(owner, AgentTurnStatusEnum.COMPLETED, "WORKFLOW_REJECTED");
            case FAILED -> finish(owner, AgentTurnStatusEnum.FAILED, "WORKFLOW_FAILED");
            case MANUAL_RETRY_REQUIRED -> finish(
                    owner, AgentTurnStatusEnum.FAILED, "WORKFLOW_MANUAL_RETRY_REQUIRED");
            case WAITING_USER_INPUT -> finish(owner, AgentTurnStatusEnum.FAILED, "WORKFLOW_INTERACTION_MISSING");
        }
    }

    public AgentTurnModel submitTurn(String userId, String threadId, String requestId, String message) {
        AgentThreadModel thread = ownedThread(userId, threadId);
        String ownerId = thread.userId();
        String ownerThreadId = thread.threadId();
        if (thread.status() == AgentThreadStatusEnum.ARCHIVED) {
            throw new AgentThreadConflictException("THREAD_ARCHIVED", "归档 Thread 不接受新消息");
        }
        String normalizedRequestId = AgentTurnInputValidator.requireClientRequestId(requestId);
        String normalizedMessage = AgentTurnInputValidator.requireText(message, "message");
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(ownerId, normalizedRequestId);
        if (duplicate.isPresent()) return duplicate.get();
        if (hasOpenInteraction(ownerId, ownerThreadId)) {
            throw new AgentThreadConflictException("THREAD_AWAITING_ANSWER", "当前 Thread 正在等待 QuestionCard 回答");
        }
        ThreadSlot slot = slots.computeIfAbsent(ownerThreadId, ignored -> new ThreadSlot());
        QueuedTurn queued;
        synchronized (slot) {
            Optional<AgentTurnModel> duplicateAfterLock = turns.findTurnByRequest(ownerId, normalizedRequestId);
            if (duplicateAfterLock.isPresent()) {
                return duplicateAfterLock.get();
            }
            if (slot.queue.size() + slot.deferred.size() >= maxPendingPerThread) {
                throw new AgentThreadConflictException("THREAD_QUEUE_FULL", "当前 Thread 排队请求已满");
            }
            if (pendingGlobal.get() >= maxPendingGlobal) {
                throw new AgentThreadConflictException("AGENT_QUEUE_FULL", "Agent 全局排队请求已满");
            }
            AgentTurnModel turn = new AgentTurnModel(
                    UUID.randomUUID().toString(), ownerThreadId, ownerId, normalizedRequestId, normalizedMessage,
                    AgentTurnStatusEnum.QUEUED, slot.queue.size() + slot.deferred.size() + 1, null, null,
                    clock.instant(), null, null
            );
            AgentItemModel initialItem = new AgentItemModel(
                    UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                    AgentItemTypeEnum.USER_MESSAGE, normalizedMessage, turn.createdAt());
            long initialSequence;
            try {
                initialSequence = turns.createTurnWithInitialItem(turn, initialItem);
            } catch (RuntimeException creationFailure) {
                Optional<AgentTurnModel> raced = turns.findTurnByRequest(ownerId, normalizedRequestId);
                if (raced.isPresent()) return raced.get();
                throw creationFailure;
            }
            if (initialSequence <= 0) {
                appendItem(initialItem);
            } else {
                events.itemCreated(AgentTurnItemPayloads.withSequence(initialItem, initialSequence));
            }
            appendItem(turn, AgentItemTypeEnum.TURN_STATE, AgentTurnItemPayloads.turnState(turn.status(), null));
            queued = new QueuedTurn(turn, null, new AtomicBoolean(false),
                    new AtomicBoolean(false), new AtomicReference<>());
            slot.queue.addLast(queued);
            pendingGlobal.incrementAndGet();
            scheduleQueueTimeout(ownerThreadId, queued);
            schedule(slot, thread);
        }
        return queued.turn;
    }

    /**
     * 将订单卡片动作作为结构化 Turn 入队；不生成自然语言消息，也不经过模型。
     */
    public AgentTurnModel submitOrderAction(
            String userId,
            String threadId,
            String requestId,
            String sourceTurnId,
            String orderId,
            AgentOrderActionTypeEnum actionType
    ) {
        AgentThreadModel thread = ownedThread(userId, threadId);
        String ownerId = thread.userId();
        String ownerThreadId = thread.threadId();
        if (thread.status() == AgentThreadStatusEnum.ARCHIVED) {
            throw new AgentThreadConflictException("THREAD_ARCHIVED", "归档 Thread 不接受订单动作");
        }
        String normalizedRequestId = AgentTurnInputValidator.requireClientRequestId(requestId);
        AgentOrderActionInput action = new AgentOrderActionInput(sourceTurnId, orderId, actionType);
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(ownerId, normalizedRequestId);
        if (duplicate.isPresent()) {
            return requireMatchingOrderActionDuplicate(duplicate.get(), ownerId, ownerThreadId, action);
        }
        AgentTurnModel source = turns.findTurn(ownerId, action.sourceTurnId())
                .filter(candidate -> candidate.threadId().equals(ownerThreadId))
                .orElseThrow(() -> new AgentThreadConflictException(
                        "SOURCE_TURN_NOT_FOUND", "订单动作来源 Turn 不属于当前 Thread"));
        if (!sourceContainsOrderFact(source, action.orderId())) {
            throw new AgentThreadConflictException("ORDER_FACT_NOT_FOUND", "来源 Turn 中没有可验证的订单事实");
        }
        if (hasOpenInteraction(ownerId, ownerThreadId)) {
            throw new AgentThreadConflictException("THREAD_AWAITING_ANSWER", "当前 Thread 正在等待 QuestionCard 回答");
        }
        ThreadSlot slot = slots.computeIfAbsent(ownerThreadId, ignored -> new ThreadSlot());
        synchronized (slot) {
            Optional<AgentTurnModel> duplicateAfterLock = turns.findTurnByRequest(ownerId, normalizedRequestId);
            if (duplicateAfterLock.isPresent()) {
                return requireMatchingOrderActionDuplicate(
                        duplicateAfterLock.get(), ownerId, ownerThreadId, action);
            }
            if (slot.queue.size() + slot.deferred.size() >= maxPendingPerThread) {
                throw new AgentThreadConflictException("THREAD_QUEUE_FULL", "当前 Thread 排队请求已满");
            }
            if (pendingGlobal.get() >= maxPendingGlobal) {
                throw new AgentThreadConflictException("AGENT_QUEUE_FULL", "Agent 全局排队请求已满");
            }
            String input = "订单动作 " + action.actionType().name() + " · " + action.orderId();
            AgentTurnModel turn = new AgentTurnModel(
                    UUID.randomUUID().toString(), ownerThreadId, ownerId, normalizedRequestId, input,
                    AgentTurnStatusEnum.QUEUED, slot.queue.size() + slot.deferred.size() + 1, null, null,
                    clock.instant(), null, null, null, 0L,
                    AgentTurnInputKindEnum.ORDER_ACTION, action);
            AgentItemModel initialItem = new AgentItemModel(
                    UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                    AgentItemTypeEnum.ORDER_ACTION_REQUEST, AgentTurnItemPayloads.orderAction(action), turn.createdAt());
            long initialSequence;
            try {
                initialSequence = turns.createTurnWithInitialItem(turn, initialItem);
            } catch (RuntimeException creationFailure) {
                Optional<AgentTurnModel> raced = turns.findTurnByRequest(ownerId, normalizedRequestId);
                if (raced.isPresent()) {
                    return requireMatchingOrderActionDuplicate(raced.get(), ownerId, ownerThreadId, action);
                }
                throw creationFailure;
            }
            if (initialSequence <= 0) {
                appendItem(initialItem);
            } else {
                events.itemCreated(AgentTurnItemPayloads.withSequence(initialItem, initialSequence));
            }
            appendItem(turn, AgentItemTypeEnum.TURN_STATE, AgentTurnItemPayloads.turnState(turn.status(), null));
            QueuedTurn queued = new QueuedTurn(turn, null, new AtomicBoolean(false),
                    new AtomicBoolean(false), new AtomicReference<>());
            slot.queue.addLast(queued);
            pendingGlobal.incrementAndGet();
            scheduleQueueTimeout(ownerThreadId, queued);
            schedule(slot, thread);
            return turn;
        }
    }

    public boolean cancel(String userId, String turnId) {
        Optional<AgentTurnModel> found = turns.findTurn(userId, turnId);
        if (found.isEmpty()) return false;
        AgentTurnModel turn = found.get();
        ThreadSlot slot = slots.computeIfAbsent(turn.threadId(), ignored -> new ThreadSlot());
        QueuedTurn queuedToCancel = null;
        QueuedTurn activeToCancel = null;
        boolean activeContextCancelled = false;
        synchronized (slot) {
            for (QueuedTurn queued : slot.queue) {
                if (queued.turn.turnId().equals(turnId)) {
                    queued.cancelled.set(true);
                    slot.queue.remove(queued);
                    pendingGlobal.decrementAndGet();
                    queuedToCancel = queued;
                    break;
                }
            }
            if (queuedToCancel == null && slot.active != null && slot.active.turn.turnId().equals(turnId)) {
                slot.active.cancelled.set(true);
                AgentExecutionContext context = slot.active.executionContext.get();
                if (context != null) {
                    context.cancel();
                    activeContextCancelled = true;
                }
                activeToCancel = slot.active;
            }
            if (queuedToCancel == null && activeToCancel == null) {
                for (QueuedTurn deferred : slot.deferred) {
                    if (deferred.turn.turnId().equals(turnId)) {
                        deferred.cancelled.set(true);
                        slot.deferred.remove(deferred);
                        pendingGlobal.decrementAndGet();
                        queuedToCancel = deferred;
                        break;
                    }
                }
            }
        }
        if (queuedToCancel != null) {
            finish(queuedToCancel.turn, AgentTurnStatusEnum.CANCELLED, "CLIENT_CANCELLED");
            return true;
        }
        if (activeToCancel != null) {
            return true;
        }
        return turn.status() == AgentTurnStatusEnum.CANCELLED;
    }

    private void schedule(ThreadSlot slot, AgentThreadModel thread) {
        if (slot.running) return;
        slot.running = true;
        executor.execute(() -> drain(slot, thread));
    }

    private void drain(ThreadSlot slot, AgentThreadModel thread) {
        try {
            for (;;) {
                QueuedTurn execution;
                synchronized (slot) {
                    execution = slot.queue.pollFirst();
                    if (execution == null) {
                        slot.running = false;
                        slot.active = null;
                        return;
                    }
                    slot.active = execution;
                    pendingGlobal.decrementAndGet();
                }
                runOne(slot, thread, execution);
                promoteDeferredIfReady(slot, thread);
            }
        } finally {
            synchronized (slot) {
                slot.running = false;
                slot.active = null;
                if (!slot.queue.isEmpty()) schedule(slot, thread);
            }
        }
    }

    private void runOne(ThreadSlot slot, AgentThreadModel thread, QueuedTurn execution) {
        AgentTurnModel turn = execution.turn;
        if (execution.cancelled.get()) {
            finish(turn, execution.timedOut.get() ? AgentTurnStatusEnum.TIMED_OUT : AgentTurnStatusEnum.CANCELLED,
                    execution.timedOut.get() ? "QUEUE_WAIT_TIMEOUT" : "CLIENT_CANCELLED");
            return;
        }
        if (execution.continuation() && hasOpenInteraction(turn.userId(), turn.threadId())) {
            synchronized (slot) {
                slot.deferred.addLast(execution);
                pendingGlobal.incrementAndGet();
            }
            metrics.observeFailure("CONTINUATION_DEFERRED_FOR_QUESTION");
            return;
        }
        Instant started;
        try {
            if (execution.questionAnswer() && !matchesEnqueuedQuestionCard(turn)) {
                finish(turn, AgentTurnStatusEnum.FAILED, "QUESTION_ANSWER_STALE");
                return;
            }
            if (!execution.questionAnswer()
                    && hasBlockingInteraction(turn)) {
                finish(turn, AgentTurnStatusEnum.FAILED, "THREAD_AWAITING_ANSWER");
                return;
            }
            started = clock.instant();
            metrics.observeQueueWait(Duration.between(turn.createdAt(), started));
            if (execution.questionAnswer() && questionCards != null) {
                questionCards.find(turn.userId(), turn.questionAnswerInput().questionId())
                        .ifPresent(question -> metrics.observeWorkflowWait(
                                Duration.between(question.createdAt(), started)));
            }
        } catch (RuntimeException gateFailure) {
            metrics.observeFailure("QUESTION_GATE_FAILED");
            finish(turn, AgentTurnStatusEnum.FAILED, "QUESTION_GATE_FAILED");
            return;
        }
        AgentTurnModel active;
        AgentExecutionContext executionContext;
        ScheduledFuture<?> timeout;
        try {
            active = turn.active(started);
            if (!updateTurn(turn, active)) {
                return;
            }
            appendItem(active, AgentItemTypeEnum.TURN_STATE, AgentTurnItemPayloads.turnState(active.status(), null));
            executionContext = new AgentExecutionContext(clock, started.plus(turnTimeout));
            execution.executionContext.set(executionContext);
            if (execution.cancelled.get()) {
                executionContext.cancel();
            }
            if (execution.continuation() && !continuationEnabled) {
                appendItem(active, AgentItemTypeEnum.AGENT_DECISION,
                        AgentTurnItemPayloads.decision(AgentDecisionTypeEnum.FALLBACK,
                                active.continuationInput().cycleNo(), active.workflowRunId(),
                                "CONTINUATION_DISABLED"));
                appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE,
                        "自动续跑当前已关闭；已保留订单结果，你可以继续手动查询最新状态。");
                finish(active, AgentTurnStatusEnum.COMPLETED, "CONTINUATION_DISABLED");
                return;
            }
            if (execution.continuation()
                    && active.continuationInput().cycleNo() > maxAgentCycles) {
                appendItem(active, AgentItemTypeEnum.AGENT_DECISION,
                        AgentTurnItemPayloads.decision(AgentDecisionTypeEnum.STOP_LIMIT,
                                active.continuationInput().cycleNo(), active.workflowRunId(),
                                "MAX_AGENT_CYCLES"));
                appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE,
                        "已达到本次订单处理的最大自动决策轮次，请继续使用查询或人工操作。");
                finish(active, AgentTurnStatusEnum.COMPLETED, "MAX_AGENT_CYCLES");
                return;
            }
            timeout = scheduler.schedule(
                    () -> {
                        execution.timedOut.set(true);
                        execution.cancelled.set(true);
                        executionContext.cancel();
                    }, turnTimeout.toMillis(), TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException activationFailure) {
            metrics.observeFailure("TURN_ACTIVATION_FAILED");
            finish(turn, AgentTurnStatusEnum.FAILED, "TURN_ACTIVATION_FAILED");
            return;
        }
        try {
            AgentTurnCoordinator.AgentCoordinatorResult result;
            if (execution.orderAction()) {
                result = executionRouter.route(
                        thread, active, List.of(), execution.answers(), executionContext);
            } else {
                var assembly = contextAssembler.assembleWithReport(thread, active.turnId(), active.input());
                executionContext.checkActive();
                metrics.observeContext(assembly.report().estimatedTokens(), assembly.report().compressed(),
                        assembly.report().degraded());
                appendItem(active, AgentItemTypeEnum.EXECUTION_EVENT, AgentTurnItemPayloads.context(assembly.report()));
                result = executionRouter.route(
                        thread, active, assembly.items(), execution.answers(), executionContext);
            }
            if (execution.cancelled.get() || executionContext.cancelled()) {
                finish(active, execution.timedOut.get() ? AgentTurnStatusEnum.TIMED_OUT : AgentTurnStatusEnum.CANCELLED,
                        execution.timedOut.get() ? "TURN_TIMEOUT" : "CLIENT_CANCELLED");
                return;
            }
            for (AgentTurnCoordinator.AgentItemDraft draft : result.items()) {
                executionContext.checkActive();
                AgentItemTypeEnum draftType = AgentTurnItemPayloads.parseType(draft.type());
                if (draftType != AgentItemTypeEnum.WORKFLOW_STARTED) {
                    appendItem(active, draftType, draft.payload());
                }
            }
            appendDecision(active, result);
            if (result.questionCard() != null || result.workflowCheckpoint() != null) {
                executionContext.checkActive();
                if (execution.questionAnswer()
                        || execution.workflowDecision()) {
                    if (!result.assistantMessage().isBlank()) {
                        appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE, result.assistantMessage());
                    }
                    finish(active, AgentTurnStatusEnum.COMPLETED, null);
                    return;
                }
                AgentTurnModel waiting = active.workflow(result.workflowRunId(), AgentTurnStatusEnum.WAITING_USER_INPUT);
                if (!updateTurn(active, waiting)) {
                    return;
                }
                appendItem(waiting, AgentItemTypeEnum.TURN_STATE, AgentTurnItemPayloads.turnState(waiting.status(), null));
                return;
            }
            if (execution.questionAnswer() || execution.workflowDecision()) {
                boolean awaitingExternalAction = result.items().stream()
                        .anyMatch(item -> "EXTERNAL_ACTION_STATUS".equals(item.type()));
                if (awaitingExternalAction) {
                    AgentTurnModel waiting = active.workflow(result.workflowRunId(),
                            AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION);
                    if (!updateTurn(active, waiting)) {
                        return;
                    }
                    appendItem(waiting, AgentItemTypeEnum.TURN_STATE,
                            AgentTurnItemPayloads.turnState(waiting.status(), null));
                    return;
                }
            }
            executionContext.checkActive();
            if (!result.assistantMessage().isBlank()) {
                appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE, result.assistantMessage());
            }
            if (execution.questionAnswer()
                    && !"REJECTED".equals(result.decisionCode())
                    && !closeQuestionAnswer(active)) {
                finish(active, AgentTurnStatusEnum.FAILED, "QUESTION_ANSWER_CLOSE_FAILED");
                return;
            }
            finish(active, AgentTurnStatusEnum.COMPLETED, null);
        } catch (RuntimeException failure) {
            boolean timedOut = failure instanceof AgentExecutionTimeoutException || execution.timedOut.get();
            boolean cancelled = timedOut
                    || failure instanceof AgentExecutionCancelledException
                    || execution.cancelled.get();
            if (!cancelled) {
                if (execution.continuation()) {
                    try {
                        int cycleNo = active.continuationInput() == null
                                ? 0 : active.continuationInput().cycleNo();
                        appendItem(active, AgentItemTypeEnum.AGENT_DECISION,
                                AgentTurnItemPayloads.decision(AgentDecisionTypeEnum.FALLBACK,
                                        cycleNo, active.workflowRunId(), "CONTINUATION_FAILED"));
                        appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE,
                                "已保留已执行的订单结果，但自动续跑未完成；你仍可以继续查询最新状态。");
                    } catch (RuntimeException fallbackFailure) {
                        failure.addSuppressed(fallbackFailure);
                    }
                }
                try {
                    appendItem(active, AgentItemTypeEnum.ERROR,
                            SAFE_EXECUTION_ERROR);
                } catch (RuntimeException itemFailure) {
                    failure.addSuppressed(itemFailure);
                }
            }
            if (!cancelled) metrics.observeFailure("AGENT_EXECUTION_FAILED");
            finish(active, timedOut ? AgentTurnStatusEnum.TIMED_OUT
                            : cancelled ? AgentTurnStatusEnum.CANCELLED : AgentTurnStatusEnum.FAILED,
                    timedOut ? "TURN_TIMEOUT"
                            : cancelled ? "CLIENT_CANCELLED" : "AGENT_EXECUTION_FAILED");
        } finally {
            timeout.cancel(false);
            execution.executionContext.set(null);
            slot.active = null;
        }
    }

    private void finish(AgentTurnModel active, AgentTurnStatusEnum status, String code) {
        Instant finishedAt = clock.instant();
        if (active.questionAnswerInput() != null && isFailureTerminal(status)) {
            releaseQuestionAnswer(active);
        }
        AgentTurnModel terminal = active.terminal(status, code, finishedAt);
        if (!updateTurn(active, terminal)) {
            return;
        }
        if (terminal.startedAt() != null && terminal.finishedAt() != null) {
            metrics.observeTurn(Duration.between(terminal.startedAt(), terminal.finishedAt()), status.name());
        }
        appendItem(terminal, AgentItemTypeEnum.TURN_STATE, AgentTurnItemPayloads.turnState(status, code));
    }

    private boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
        boolean updated = turns.updateTurn(expected, next);
        if (!updated) {
            metrics.observeFailure("TURN_VERSION_CONFLICT");
        }
        return updated;
    }

    private void appendItem(AgentTurnModel turn, AgentItemTypeEnum type, String payload) {
        String boundedPayload = type == AgentItemTypeEnum.TOOL_RESULT && payload != null
                && payload.length() > toolResultMaxCharacters
                ? payload.substring(0, toolResultMaxCharacters) + "…[TOOL_RESULT_TRUNCATED]"
                : payload;
        appendItem(new AgentItemModel(
                UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0, type, boundedPayload, clock.instant()
        ));
    }

    private void appendDecision(
            AgentTurnModel turn,
            AgentTurnCoordinator.AgentCoordinatorResult result
    ) {
        if (result == null || result.decision() == null) {
            return;
        }
        int cycleNo = turn.continuationInput() == null ? 0 : turn.continuationInput().cycleNo();
        String runId = result.workflowRunId() == null ? turn.workflowRunId() : result.workflowRunId();
        appendItem(turn, AgentItemTypeEnum.AGENT_DECISION,
                AgentTurnItemPayloads.decision(result.decision(), cycleNo, runId, result.decisionCode()));
    }

    private void appendItem(AgentItemModel item) {
        AgentItemModel bounded = item.type() == AgentItemTypeEnum.TOOL_RESULT && item.payload() != null
                && item.payload().length() > toolResultMaxCharacters
                ? new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), item.sequence(), item.type(),
                item.payload().substring(0, toolResultMaxCharacters) + "…[TOOL_RESULT_TRUNCATED]", item.createdAt())
                : item;
        long sequence = items.appendItem(bounded);
        events.itemCreated(new AgentItemModel(bounded.itemId(), bounded.threadId(), bounded.turnId(), sequence,
                bounded.type(), bounded.payload(), bounded.createdAt()));
    }

    private boolean sourceContainsOrderFact(AgentTurnModel source, String orderId) {
        String marker = "\"orderId\":\"" + AgentTurnItemPayloads.escape(orderId) + "\"";
        List<AgentItemModel> threadItems = allItems(source.userId(), source.threadId());
        Set<String> foldedTurnIds = new HashSet<>();
        foldedTurnIds.add(source.turnId());
        boolean changed;
        do {
            changed = false;
            for (AgentItemModel item : threadItems) {
                if (item == null || item.turnId() == null || foldedTurnIds.contains(item.turnId())) {
                    continue;
                }
                String payload = item.payloadJson() == null ? "" : item.payloadJson();
                boolean belongsToSource = item.type() == AgentItemTypeEnum.ORDER_ACTION_REQUEST
                        && payload.contains("\"sourceTurnId\":\""
                        + AgentTurnItemPayloads.escape(source.turnId()) + "\"");
                if (!belongsToSource && item.type() == AgentItemTypeEnum.AGENT_CONTINUATION) {
                    belongsToSource = payload.contains("\"rootTurnId\":\""
                            + AgentTurnItemPayloads.escape(source.turnId()) + "\"");
                }
                if (belongsToSource) {
                    changed |= foldedTurnIds.add(item.turnId());
                }
            }
        } while (changed);
        return threadItems.stream()
                .filter(item -> item != null && foldedTurnIds.contains(item.turnId()))
                .filter(item -> item.type() == AgentItemTypeEnum.ORDER_DETAIL
                        || item.type() == AgentItemTypeEnum.ORDER_LIST)
                .anyMatch(item -> item.payloadJson() != null && item.payloadJson().contains(marker));
    }

    /** 统一取完整 Item 链，避免订单事实在长 Thread 中落出最近 500 条窗口。 */
    private List<AgentItemModel> allItems(String userId, String threadId) {
        List<AgentItemModel> result = new java.util.ArrayList<>();
        long cursor = 0L;
        for (int pageNo = 0; pageNo < 100; pageNo++) {
            List<AgentItemModel> page = items.listItems(userId, threadId, cursor, 501);
            if (page == null || page.isEmpty()) {
                break;
            }
            long next = cursor;
            for (AgentItemModel item : page) {
                if (item == null || item.sequence() <= cursor) {
                    continue;
                }
                result.add(item);
                next = Math.max(next, item.sequence());
            }
            if (next <= cursor || page.size() < 501) {
                break;
            }
            cursor = next;
        }
        return result;
    }

    private void scheduleQueueTimeout(String threadId, QueuedTurn queued) {
        scheduler.schedule(() -> expireQueued(threadId, queued), waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void promoteDeferredIfReady(ThreadSlot slot, AgentThreadModel thread) {
        if (hasOpenInteraction(thread.userId(), thread.threadId())) {
            return;
        }
        synchronized (slot) {
            if (slot.deferred.isEmpty()) {
                return;
            }
            QueuedTurn continuation = slot.deferred.pollFirst();
            if (continuation != null) {
                slot.queue.addFirst(continuation);
            }
        }
    }

    private void expireQueued(String threadId, QueuedTurn target) {
        ThreadSlot slot = slots.get(threadId);
        if (slot == null) return;
        synchronized (slot) {
            if (!slot.queue.remove(target)) return;
            target.timedOut.set(true);
            target.cancelled.set(true);
            pendingGlobal.decrementAndGet();
        }
        finish(target.turn, AgentTurnStatusEnum.TIMED_OUT, "QUEUE_WAIT_TIMEOUT");
    }

    private AgentTurnModel requireMatchingOrderActionDuplicate(
            AgentTurnModel existing,
            String userId,
            String threadId,
            AgentOrderActionInput action
    ) {
        boolean matches = existing.userId().equals(userId)
                && existing.threadId().equals(threadId)
                && existing.inputKind() == AgentTurnInputKindEnum.ORDER_ACTION
                && action.equals(existing.orderActionInput());
        if (!matches) {
            throw new AgentThreadConflictException(
                    "CLIENT_REQUEST_CONFLICT", "clientRequestId 已用于不同的订单动作");
        }
        return existing;
    }

    private boolean matchesEnqueuedQuestionCard(AgentTurnModel turn) {
        AgentQuestionAnswerInput input = turn.questionAnswerInput();
        if (input == null || questionCards == null) {
            return false;
        }
        return questionCards.find(turn.userId(), input.questionId())
                .filter(question -> question.threadId().equals(turn.threadId()))
                .filter(question -> java.util.Objects.equals(question.runId(), input.runId()))
                .filter(question -> question.version() == input.enqueuedQuestionVersion())
                .filter(question -> turn.turnId().equals(question.answerTurnId()))
                .filter(question -> question.status() == AgentQuestionCardStatusEnum.OPEN)
                .filter(question -> question.answerEnqueueStatus()
                        == cn.ethan.core.agent.workflow.AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED)
                .filter(question -> input.action() == AgentQuestionCardAnswerActionEnum.CANCEL
                        || matchesQuestionAnswerSchema(question, input))
                .isPresent();
    }

    private boolean matchesQuestionAnswerSchema(
            cn.ethan.core.agent.workflow.AgentQuestionCardModel question,
            AgentQuestionAnswerInput input
    ) {
        try {
            return question.validateAnswers(input.answers()).equals(input.answers());
        } catch (RuntimeException invalidAnswer) {
            return false;
        }
    }

    private boolean closeQuestionAnswer(AgentTurnModel turn) {
        if (questionCards == null || turn.questionAnswerInput() == null) {
            return true;
        }
        AgentQuestionAnswerInput input = turn.questionAnswerInput();
        AgentQuestionCardStatusEnum terminal = input.action() == AgentQuestionCardAnswerActionEnum.CANCEL
                ? AgentQuestionCardStatusEnum.CANCELLED : AgentQuestionCardStatusEnum.ANSWERED;
        return questionCards.closeAnswerTurn(turn.userId(), input.questionId(), input.enqueuedQuestionVersion(),
                turn.turnId(), terminal, clock.instant());
    }

    private void releaseQuestionAnswer(AgentTurnModel turn) {
        if (questionCards == null || turn.questionAnswerInput() == null) {
            return;
        }
        AgentQuestionAnswerInput input = turn.questionAnswerInput();
        questionCards.releaseAnswerTurn(turn.userId(), input.questionId(), input.enqueuedQuestionVersion(),
                turn.turnId());
    }

    private boolean isFailureTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    private AgentThreadModel ownedThread(String userId, String threadId) {
        return threads.get(userId, threadId);
    }

    private static final class ThreadSlot {
        private final Deque<QueuedTurn> queue = new ArrayDeque<>();
        private final Deque<QueuedTurn> deferred = new ArrayDeque<>();
        private boolean running;
        private QueuedTurn active;
    }

    private record QueuedTurn(
            AgentTurnModel turn,
            AgentQuestionAnswerInput questionAnswerInput,
            AtomicBoolean cancelled,
            AtomicBoolean timedOut,
            AtomicReference<AgentExecutionContext> executionContext
    ) {
        private boolean questionAnswer() {
            return questionAnswerInput != null;
        }

        private boolean workflowDecision() {
            return turn.workflowDecisionInput() != null;
        }

        private boolean orderAction() {
            return turn.inputKind() == AgentTurnInputKindEnum.ORDER_ACTION
                    && turn.orderActionInput() != null;
        }

        private boolean continuation() {
            return turn.inputKind() == AgentTurnInputKindEnum.AGENT_CONTINUATION
                    && turn.continuationInput() != null;
        }

        private Map<String, String> answers() {
            return questionAnswerInput == null ? Map.of() : questionAnswerInput.answers();
        }
    }
}
