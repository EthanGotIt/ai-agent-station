package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
public final class AgentTurnRuntimeService {

    private static final long FAILURE_RECONCILIATION_MAX_BACKOFF_MILLIS = 30_000L;

    private final AgentThreadStore threadStore;
    private final AgentTurnStore turns;
    private final AgentItemStore items;
    private final AgentWorkflowQuestionStore questions;
    private final AgentWorkflowAnswerAdmission answerAdmission;
    private final AgentWorkflowAnswerFailureReconciler failureReconciler;
    private final AgentThreadService threads;
    private final AgentContextAssembler contextAssembler;
    private final AgentTurnCoordinator coordinator;
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
    private final AtomicInteger pendingGlobal = new AtomicInteger();
    private final Map<String, ThreadSlot> slots = new ConcurrentHashMap<>();
    private final Map<String, PendingFailureReconciliation> pendingFailureReconciliations = new ConcurrentHashMap<>();

    public AgentTurnRuntimeService(
            AgentThreadStore threadStore,
            AgentTurnStore turns,
            AgentItemStore items,
            AgentWorkflowQuestionStore questions,
            AgentWorkflowAnswerAdmission answerAdmission,
            AgentWorkflowAnswerFailureReconciler failureReconciler,
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
        this(threadStore, turns, items, questions, answerAdmission, failureReconciler,
                threads, contextAssembler, coordinator,
                events, executor, scheduler, clock, maxPendingPerThread, maxPendingGlobal, waitTimeout, turnTimeout,
                toolResultMaxCharacters, AgentRuntimeMetrics.noop());
    }

    public AgentTurnRuntimeService(
            AgentThreadStore threadStore,
            AgentTurnStore turns,
            AgentItemStore items,
            AgentWorkflowQuestionStore questions,
            AgentWorkflowAnswerAdmission answerAdmission,
            AgentWorkflowAnswerFailureReconciler failureReconciler,
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
            AgentRuntimeMetrics metrics
    ) {
        this.threadStore = threadStore;
        this.turns = turns;
        this.items = items;
        this.questions = questions;
        this.answerAdmission = answerAdmission;
        this.failureReconciler = failureReconciler;
        this.threads = threads;
        this.contextAssembler = contextAssembler;
        this.coordinator = coordinator;
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
    }

    public void recoverPersistedTurns() {
        for (AgentTurnModel candidate : turns.listWorkflowAnswerReconciliationCandidates()) {
            AgentTurnStatusEnum status = isFailureTerminal(candidate.status())
                    ? candidate.status() : AgentTurnStatusEnum.FAILED;
            reconcileAnswerFailure(candidate, status,
                    candidate.errorCode() == null ? "WORKFLOW_ANSWER_RECONCILIATION" : candidate.errorCode());
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
                if (persisted.workflowAnswerInput() != null) {
                    if (!persisted.createdAt().plus(waitTimeout).isAfter(clock.instant())) {
                        finish(persisted, AgentTurnStatusEnum.TIMED_OUT, "QUEUE_WAIT_TIMEOUT");
                        continue;
                    }
                    try {
                        if (!matchesEnqueuedQuestion(persisted)) {
                            finish(persisted, AgentTurnStatusEnum.FAILED, "WORKFLOW_ANSWER_STALE");
                            continue;
                        }
                    } catch (RuntimeException questionFailure) {
                        metrics.observeFailure("WORKFLOW_ANSWER_RECOVERY_QUERY_FAILED");
                        reconcileAnswerFailure(
                                persisted, AgentTurnStatusEnum.FAILED, "WORKFLOW_ANSWER_RECOVERY_FAILED");
                        continue;
                    }
                }
                boolean present = slot.queue.stream()
                        .anyMatch(queued -> queued.turn.turnId().equals(persisted.turnId()));
                if (present) {
                    continue;
                }
                if (slot.queue.size() >= maxPendingPerThread || pendingGlobal.get() >= maxPendingGlobal) {
                    finish(persisted, AgentTurnStatusEnum.FAILED, "RUNTIME_QUEUE_OVERFLOW");
                    continue;
                }
                QueuedTurn queued = new QueuedTurn(
                        persisted, persisted.workflowAnswerInput(), new AtomicBoolean(false),
                        new AtomicBoolean(false), new AtomicReference<>());
                slot.queue.addLast(queued);
                pendingGlobal.incrementAndGet();
                publishTurn(persisted);
                scheduleQueueTimeout(thread.threadId(), queued);
                schedule(slot, thread);
            }
        }
    }

    public AgentTurnModel submitTurn(String userId, String threadId, String requestId, String message) {
        AgentThreadModel thread = ownedThread(userId, threadId);
        if (thread.status() == AgentThreadStatusEnum.ARCHIVED) {
            throw new AgentThreadConflictException("THREAD_ARCHIVED", "归档 Thread 不接受新消息");
        }
        requireClientRequestId(requestId);
        requireText(message, "message");
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(userId, requestId);
        if (duplicate.isPresent()) return duplicate.get();
        if (questions.findOpenQuestion(userId, threadId).isPresent()) {
            throw new AgentThreadConflictException("THREAD_AWAITING_ANSWER", "当前 Thread 正在等待 QuestionCard 回答");
        }
        ThreadSlot slot = slots.computeIfAbsent(threadId, ignored -> new ThreadSlot());
        QueuedTurn queued;
        synchronized (slot) {
            Optional<AgentTurnModel> duplicateAfterLock = turns.findTurnByRequest(userId, requestId);
            if (duplicateAfterLock.isPresent()) {
                return duplicateAfterLock.get();
            }
            if (slot.queue.size() >= maxPendingPerThread) {
                throw new AgentThreadConflictException("THREAD_QUEUE_FULL", "当前 Thread 排队请求已满");
            }
            if (pendingGlobal.get() >= maxPendingGlobal) {
                throw new AgentThreadConflictException("AGENT_QUEUE_FULL", "Agent 全局排队请求已满");
            }
            AgentTurnModel turn = new AgentTurnModel(
                    UUID.randomUUID().toString(), threadId, userId, requestId, message,
                    AgentTurnStatusEnum.QUEUED, slot.queue.size() + 1, null, null,
                    clock.instant(), null, null
            );
            AgentItemModel initialItem = new AgentItemModel(
                    UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0,
                    AgentItemTypeEnum.USER_MESSAGE, message, turn.createdAt());
            long initialSequence;
            try {
                initialSequence = turns.createTurnWithInitialItem(turn, initialItem);
            } catch (RuntimeException creationFailure) {
                Optional<AgentTurnModel> raced = turns.findTurnByRequest(userId, requestId);
                if (raced.isPresent()) return raced.get();
                throw creationFailure;
            }
            if (initialSequence <= 0) {
                appendItem(initialItem);
            } else {
                events.itemCreated(withSequence(initialItem, initialSequence));
            }
            appendItem(turn, AgentItemTypeEnum.TURN_STATE, turnStatePayload(turn.status(), null));
            queued = new QueuedTurn(turn, null, new AtomicBoolean(false),
                    new AtomicBoolean(false), new AtomicReference<>());
            slot.queue.addLast(queued);
            pendingGlobal.incrementAndGet();
            publishTurn(turn);
            scheduleQueueTimeout(thread.threadId(), queued);
            schedule(slot, thread);
        }
        return queued.turn;
    }

    public AgentTurnModel answerQuestion(
            String userId,
            String threadId,
            String requestId,
            String runId,
            String questionId,
            String checkpointId,
            long expectedVersion,
            Map<String, String> answers
    ) {
        AgentThreadModel thread = ownedThread(userId, threadId);
        requireClientRequestId(requestId);
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("answers 不能为空");
        }
        Map<String, String> persistedAnswers = normalizeSubmittedAnswers(answers);
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(userId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingAnswerDuplicate(duplicate.get(), userId, threadId, runId,
                    questionId, checkpointId, expectedVersion, persistedAnswers);
        }
        AgentWorkflowQuestionModel question = questions.findOpenQuestion(userId, threadId)
                .orElseThrow(() -> new AgentThreadConflictException("QUESTION_NOT_OPEN", "QuestionCard 已关闭"));
        if (!question.runId().equals(runId) || !question.questionId().equals(questionId)
                || !question.checkpointId().equals(checkpointId) || question.version() != expectedVersion
                || question.answerEnqueueStatus()
                != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE) {
            throw new AgentThreadConflictException("WORKFLOW_VERSION_CONFLICT", "QuestionCard 检查点或版本已变化");
        }
        ThreadSlot slot = slots.computeIfAbsent(threadId, ignored -> new ThreadSlot());
        synchronized (slot) {
            Optional<AgentTurnModel> duplicateAfterLock = turns.findTurnByRequest(userId, requestId);
            if (duplicateAfterLock.isPresent()) {
                return requireMatchingAnswerDuplicate(duplicateAfterLock.get(), userId, threadId, runId,
                        questionId, checkpointId, expectedVersion, persistedAnswers);
            }
            if (slot.queue.size() >= maxPendingPerThread || pendingGlobal.get() >= maxPendingGlobal) {
                throw new AgentThreadConflictException("AGENT_QUEUE_FULL", "回答请求无法入队");
            }
            AgentWorkflowAnswerAdmissionResult admission = answerAdmission.admit(
                    new AgentWorkflowAnswerAdmissionCommand(
                            userId, threadId, requestId, slot.queue.size() + 1,
                            runId, questionId, checkpointId, expectedVersion, persistedAnswers));
            if (!admission.newlyAdmitted()) {
                return admission.turn();
            }
            AgentTurnModel turn = admission.turn();
            QueuedTurn queued = new QueuedTurn(turn, turn.workflowAnswerInput(),
                    new AtomicBoolean(false), new AtomicBoolean(false), new AtomicReference<>());
            boolean added = false;
            try {
                events.itemCreated(admission.initialItem());
                appendItem(turn, AgentItemTypeEnum.TURN_STATE, turnStatePayload(turn.status(), null));
                slot.queue.addLast(queued);
                added = true;
                pendingGlobal.incrementAndGet();
                publishTurn(turn);
                scheduleQueueTimeout(thread.threadId(), queued);
                schedule(slot, thread);
                return turn;
            } catch (RuntimeException submissionFailure) {
                if (added && slot.queue.remove(queued)) {
                    pendingGlobal.decrementAndGet();
                }
                if (slot.active == null) {
                    slot.running = false;
                }
                failAdmittedAnswerSubmission(turn, submissionFailure);
                throw submissionFailure;
            }
        }
    }

    public AgentTurnModel answerQuestion(
            String userId,
            String requestId,
            String runId,
            String questionId,
            String checkpointId,
            long expectedVersion,
            Map<String, String> answers
    ) {
        requireClientRequestId(requestId);
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("answers 不能为空");
        }
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(userId, requestId);
        if (duplicate.isPresent()) {
            return requireMatchingAnswerDuplicate(duplicate.get(), userId, duplicate.get().threadId(), runId,
                    questionId, checkpointId, expectedVersion, normalizeSubmittedAnswers(answers));
        }
        AgentWorkflowQuestionModel question = questions.findOpenQuestionByRun(userId, runId)
                .orElseThrow(() -> new AgentThreadConflictException("QUESTION_NOT_OPEN", "QuestionCard 已关闭"));
        return answerQuestion(userId, question.threadId(), requestId, runId, questionId,
                checkpointId, expectedVersion, answers);
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
        }
        if (queuedToCancel != null) {
            finish(queuedToCancel.turn, AgentTurnStatusEnum.CANCELLED, "CLIENT_CANCELLED");
            return true;
        }
        if (activeToCancel != null) {
            if (activeContextCancelled && activeToCancel.workflowAnswer()) {
                reconcileAnswerFailure(
                        activeToCancel.turn, AgentTurnStatusEnum.CANCELLED, "CLIENT_CANCELLED");
            }
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
        Instant started;
        try {
            if (execution.workflowAnswer() && !matchesEnqueuedQuestion(turn)) {
                finish(turn, AgentTurnStatusEnum.FAILED, "WORKFLOW_ANSWER_STALE");
                return;
            }
            if (!execution.workflowAnswer()
                    && questions.findOpenQuestion(turn.userId(), turn.threadId()).isPresent()) {
                finish(turn, AgentTurnStatusEnum.FAILED, "THREAD_AWAITING_ANSWER");
                return;
            }
            started = clock.instant();
            metrics.observeQueueWait(Duration.between(turn.createdAt(), started));
            if (execution.workflowAnswer()) {
                questions.findOpenQuestionByRun(turn.userId(), turn.workflowRunId())
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
            appendItem(active, AgentItemTypeEnum.TURN_STATE, turnStatePayload(active.status(), null));
            publishTurn(active);
            executionContext = new AgentExecutionContext(clock, started.plus(turnTimeout));
            execution.executionContext.set(executionContext);
            if (execution.cancelled.get()) {
                executionContext.cancel();
            }
            timeout = scheduler.schedule(
                    () -> {
                        execution.timedOut.set(true);
                        execution.cancelled.set(true);
                        executionContext.cancel();
                        if (execution.workflowAnswer()) {
                            reconcileAnswerFailure(turn, AgentTurnStatusEnum.TIMED_OUT, "TURN_TIMEOUT");
                        }
                    }, turnTimeout.toMillis(), TimeUnit.MILLISECONDS
            );
        } catch (RuntimeException activationFailure) {
            metrics.observeFailure("TURN_ACTIVATION_FAILED");
            finish(turn, AgentTurnStatusEnum.FAILED, "TURN_ACTIVATION_FAILED");
            return;
        }
        try {
            var assembly = contextAssembler.assembleWithReport(thread, active.turnId(), active.input());
            executionContext.checkActive();
            metrics.observeContext(assembly.report().estimatedTokens(), assembly.report().compressed(),
                    assembly.report().degraded());
            appendItem(active, AgentItemTypeEnum.EXECUTION_EVENT, contextPayload(assembly.report()));
            List<AgentItemModel> context = assembly.items();
            AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(
                    thread, active, context, execution.answers(), executionContext);
            if (execution.cancelled.get() || executionContext.cancelled()) {
                finish(active, execution.timedOut.get() ? AgentTurnStatusEnum.TIMED_OUT : AgentTurnStatusEnum.CANCELLED,
                        execution.timedOut.get() ? "TURN_TIMEOUT" : "CLIENT_CANCELLED");
                return;
            }
            for (AgentTurnCoordinator.AgentItemDraft draft : result.items()) {
                executionContext.checkActive();
                AgentItemTypeEnum draftType = parseType(draft.type());
                if (!execution.workflowAnswer()
                        && draftType != AgentItemTypeEnum.WORKFLOW_STARTED
                        && draftType != AgentItemTypeEnum.WORKFLOW_QUESTION) {
                    appendItem(active, draftType, draft.payload());
                }
            }
            if (result.question() != null) {
                executionContext.checkActive();
                publishWorkflowItems(active);
                AgentTurnModel waiting = active.workflow(result.workflowRunId(), AgentTurnStatusEnum.WAITING_USER_INPUT);
                if (!updateTurn(active, waiting)) {
                    return;
                }
                appendItem(waiting, AgentItemTypeEnum.TURN_STATE, turnStatePayload(waiting.status(), null));
                publishTurn(waiting);
                return;
            }
            if (execution.workflowAnswer()) {
                publishWorkflowItems(active);
                boolean awaitingExternalAction = result.items().stream()
                        .anyMatch(item -> "EXTERNAL_ACTION_STATUS".equals(item.type()));
                if (awaitingExternalAction) {
                    AgentTurnModel waiting = active.workflow(result.workflowRunId(),
                            AgentTurnStatusEnum.WAITING_EXTERNAL_ACTION);
                    if (!updateTurn(active, waiting)) {
                        return;
                    }
                    appendItem(waiting, AgentItemTypeEnum.TURN_STATE,
                            turnStatePayload(waiting.status(), null));
                    publishTurn(waiting);
                    return;
                }
            }
            executionContext.checkActive();
            if (!result.assistantMessage().isBlank()) {
                appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE, result.assistantMessage());
            }
            finish(active, AgentTurnStatusEnum.COMPLETED, null);
        } catch (RuntimeException failure) {
            boolean timedOut = failure instanceof AgentExecutionTimeoutException || execution.timedOut.get();
            boolean cancelled = timedOut
                    || failure instanceof AgentExecutionCancelledException
                    || execution.cancelled.get();
            if (!cancelled) {
                try {
                    appendItem(active, AgentItemTypeEnum.ERROR,
                            failure.getMessage() == null ? "Agent 执行失败" : failure.getMessage());
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
        if (active.workflowAnswerInput() != null && isFailureTerminal(status)) {
            reconcileAnswerFailure(active, status, code, finishedAt);
            return;
        }
        AgentTurnModel terminal = active.terminal(status, code, finishedAt);
        if (!updateTurn(active, terminal)) {
            return;
        }
        if (terminal.startedAt() != null && terminal.finishedAt() != null) {
            metrics.observeTurn(Duration.between(terminal.startedAt(), terminal.finishedAt()), status.name());
        }
        appendItem(terminal, AgentItemTypeEnum.TURN_STATE, turnStatePayload(status, code));
        publishTurn(terminal);
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

    private AgentItemModel withSequence(AgentItemModel item, long sequence) {
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt());
    }

    private void scheduleQueueTimeout(String threadId, QueuedTurn queued) {
        scheduler.schedule(() -> expireQueued(threadId, queued), waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
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

    private void publishTurn(AgentTurnModel turn) {
        events.turnUpdated(turn);
    }

    private void publishWorkflowItems(AgentTurnModel turn) {
        items.listItems(turn.userId(), turn.threadId(), 0, 500).stream()
                .filter(item -> turn.turnId().equals(item.turnId()))
                .filter(item -> item.type() == AgentItemTypeEnum.WORKFLOW_STARTED
                        || item.type() == AgentItemTypeEnum.WORKFLOW_QUESTION
                        || item.type() == AgentItemTypeEnum.WORKFLOW_RESULT
                        || item.type() == AgentItemTypeEnum.EXTERNAL_ACTION_STATUS)
                .forEach(events::itemCreated);
    }

    private String turnStatePayload(AgentTurnStatusEnum status, String errorCode) {
        return "{\"status\":\"" + status.name() + "\",\"errorCode\":"
                + (errorCode == null ? "null" : "\"" + escape(errorCode) + "\"") + "}";
    }

    private String contextPayload(cn.ethan.core.agent.context.AgentContextBudgetReport report) {
        return "{\"kind\":\"CONTEXT_ASSEMBLED\",\"estimatedTokens\":" + report.estimatedTokens()
                + ",\"inputBudget\":" + report.inputBudget()
                + ",\"snapshotThroughSequence\":" + report.snapshotThroughSequence()
                + ",\"compressed\":" + report.compressed()
                + ",\"degraded\":" + report.degraded() + "}";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private AgentTurnModel requireMatchingAnswerDuplicate(
            AgentTurnModel existing,
            String userId,
            String threadId,
            String runId,
            String questionId,
            String checkpointId,
            long expectedVersion,
            Map<String, String> answers
    ) {
        AgentWorkflowAnswerInput input = existing.workflowAnswerInput();
        boolean matches = existing.userId().equals(userId)
                && existing.threadId().equals(threadId)
                && input != null
                && input.runId().equals(runId)
                && input.questionId().equals(questionId)
                && input.checkpointId().equals(checkpointId)
                && input.admissionExpectedVersion() == expectedVersion
                && input.answers().equals(answers);
        if (!matches) {
            throw new AgentThreadConflictException(
                    "CLIENT_REQUEST_CONFLICT", "clientRequestId 已用于不同的 Workflow 回答");
        }
        return existing;
    }

    private boolean matchesEnqueuedQuestion(AgentTurnModel turn) {
        AgentWorkflowAnswerInput input = turn.workflowAnswerInput();
        if (input == null) {
            return false;
        }
        return questions.findOpenQuestionByRun(turn.userId(), input.runId())
                .filter(question -> question.threadId().equals(turn.threadId()))
                .filter(question -> question.questionId().equals(input.questionId()))
                .filter(question -> question.checkpointId().equals(input.checkpointId()))
                .filter(question -> question.version() == input.enqueuedQuestionVersion())
                .filter(question -> turn.turnId().equals(question.answerTurnId()))
                .filter(question -> question.status() == AgentWorkflowQuestionStatusEnum.OPEN)
                .filter(question -> question.answerEnqueueStatus()
                        == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED)
                .filter(question -> matchesAnswerSchema(question, input))
                .isPresent();
    }

    private boolean matchesAnswerSchema(
            AgentWorkflowQuestionModel question,
            AgentWorkflowAnswerInput input
    ) {
        try {
            return question.validateAnswers(input.answers()).equals(input.answers());
        } catch (RuntimeException invalidAnswer) {
            return false;
        }
    }

    private boolean reconcileAnswerFailure(
            AgentTurnModel turn,
            AgentTurnStatusEnum status,
            String code
    ) {
        return reconcileAnswerFailure(turn, status, code, clock.instant());
    }

    private boolean reconcileAnswerFailure(
            AgentTurnModel turn,
            AgentTurnStatusEnum status,
            String code,
            Instant finishedAt
    ) {
        try {
            AgentWorkflowAnswerFailureReconciler.ReconciliationResult result =
                    failureReconciler.reconcileWithProjection(turn, status, code, finishedAt);
            if (!result.reconciled()) {
                scheduleFailureReconciliation(turn, status, code, finishedAt);
                return false;
            }
            pendingFailureReconciliations.remove(turn.turnId());
            if (result.retryQuestionItem() != null) {
                try {
                    events.itemCreated(result.retryQuestionItem());
                } catch (RuntimeException eventFailure) {
                    metrics.observeFailure("WORKFLOW_ANSWER_RETRY_QUESTION_EVENT_FAILED");
                }
            }
            publishFailureTerminal(turn, status, code, finishedAt);
            return true;
        } catch (RuntimeException reconciliationFailure) {
            metrics.observeFailure("WORKFLOW_ANSWER_RELEASE_FAILED");
            scheduleFailureReconciliation(turn, status, code, finishedAt);
            return false;
        }
    }

    private void scheduleFailureReconciliation(
            AgentTurnModel turn,
            AgentTurnStatusEnum status,
            String code,
            Instant finishedAt
    ) {
        PendingFailureReconciliation pending = pendingFailureReconciliations.computeIfAbsent(
                turn.turnId(), ignored -> new PendingFailureReconciliation(turn, status, code, finishedAt));
        if (!pending.scheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = pending.attempts.incrementAndGet();
        long delayMillis = attempt <= 3
                ? 100L << (attempt - 1)
                : FAILURE_RECONCILIATION_MAX_BACKOFF_MILLIS;
        try {
            scheduler.schedule(() -> {
                if (pendingFailureReconciliations.get(turn.turnId()) != pending) {
                    return;
                }
                pending.scheduled.set(false);
                reconcileAnswerFailure(
                        pending.turn, pending.status, pending.code, pending.finishedAt);
            }, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            pending.scheduled.set(false);
            metrics.observeFailure("WORKFLOW_ANSWER_RECONCILIATION_SCHEDULE_FAILED");
        }
    }

    private void publishFailureTerminal(
            AgentTurnModel turn,
            AgentTurnStatusEnum status,
            String code,
            Instant finishedAt
    ) {
        AgentTurnModel terminal = turn.terminal(status, code, finishedAt);
        if (terminal.startedAt() != null) {
            metrics.observeTurn(Duration.between(terminal.startedAt(), finishedAt), status.name());
        }
        try {
            appendItem(terminal, AgentItemTypeEnum.TURN_STATE, turnStatePayload(status, code));
            publishTurn(terminal);
        } catch (RuntimeException eventFailure) {
            metrics.observeFailure("WORKFLOW_ANSWER_TERMINAL_EVENT_FAILED");
        }
    }

    private boolean isFailureTerminal(AgentTurnStatusEnum status) {
        return status == AgentTurnStatusEnum.FAILED
                || status == AgentTurnStatusEnum.CANCELLED
                || status == AgentTurnStatusEnum.TIMED_OUT;
    }

    private void failAdmittedAnswerSubmission(AgentTurnModel turn, RuntimeException submissionFailure) {
        if (!reconcileAnswerFailure(turn, AgentTurnStatusEnum.FAILED, "ANSWER_SUBMISSION_FAILED")) {
            submissionFailure.addSuppressed(new IllegalStateException(
                    "回答提交失败已进入持久化 reconciliation"));
        }
    }

    private AgentThreadModel ownedThread(String userId, String threadId) {
        return threads.get(userId, threadId);
    }

    private AgentItemTypeEnum parseType(String value) {
        try {
            return AgentItemTypeEnum.valueOf(value);
        } catch (RuntimeException failure) {
            return AgentItemTypeEnum.EXECUTION_EVENT;
        }
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.trim().length() > 256) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 256");
        }
    }

    private void requireClientRequestId(String clientRequestId) {
        if (clientRequestId == null || clientRequestId.isBlank() || clientRequestId.length() > 128) {
            throw new IllegalArgumentException("clientRequestId 不能为空且长度不能超过 128");
        }
    }

    private Map<String, String> normalizeSubmittedAnswers(Map<String, String> answers) {
        java.util.LinkedHashMap<String, String> normalized = new java.util.LinkedHashMap<>();
        answers.forEach((name, value) -> {
            if (name == null || value == null) {
                throw new IllegalArgumentException("QuestionCard 回答字段和值不能为空");
            }
            normalized.put(name, value.trim());
        });
        return Map.copyOf(normalized);
    }

    private static final class ThreadSlot {
        private final Deque<QueuedTurn> queue = new ArrayDeque<>();
        private boolean running;
        private QueuedTurn active;
    }

    private static final class PendingFailureReconciliation {
        private final AgentTurnModel turn;
        private final AgentTurnStatusEnum status;
        private final String code;
        private final Instant finishedAt;
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicBoolean scheduled = new AtomicBoolean();

        private PendingFailureReconciliation(
                AgentTurnModel turn,
                AgentTurnStatusEnum status,
                String code,
                Instant finishedAt
        ) {
            this.turn = turn;
            this.status = status;
            this.code = code;
            this.finishedAt = finishedAt;
        }
    }

    private record QueuedTurn(
            AgentTurnModel turn,
            AgentWorkflowAnswerInput answerInput,
            AtomicBoolean cancelled,
            AtomicBoolean timedOut,
            AtomicReference<AgentExecutionContext> executionContext
    ) {
        private boolean workflowAnswer() {
            return answerInput != null;
        }

        private Map<String, String> answers() {
            return answerInput == null ? Map.of() : answerInput.answers();
        }
    }
}
