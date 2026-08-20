package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;

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

/**
 * 类型职责：以 Thread 为队列键，负责 Turn 生命周期、持久化 Item 和可恢复 HITL。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class AgentTurnRuntimeService {

    private final AgentThreadStore threadStore;
    private final AgentTurnStore turns;
    private final AgentItemStore items;
    private final AgentWorkflowQuestionStore questions;
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
    private final AtomicInteger pendingGlobal = new AtomicInteger();
    private final Map<String, ThreadSlot> slots = new ConcurrentHashMap<>();

    public AgentTurnRuntimeService(
            AgentThreadStore threadStore,
            AgentTurnStore turns,
            AgentItemStore items,
            AgentWorkflowQuestionStore questions,
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
        this.threadStore = threadStore;
        this.turns = turns;
        this.items = items;
        this.questions = questions;
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
    }

    public AgentThreadModel createThread(String userId, String title, String contextType, String contextId) {
        AgentThreadModel thread = threads.create(userId, title, contextType, contextId);
        slots.putIfAbsent(thread.threadId(), new ThreadSlot());
        return thread;
    }

    public List<AgentThreadModel> listThreads(String userId) {
        return threads.list(userId);
    }

    public AgentThreadModel getThread(String userId, String threadId) {
        return threads.get(userId, threadId);
    }

    public void recoverPersistedTurns() {
        for (AgentTurnModel persisted : turns.listRecoverableTurns()) {
            AgentThreadModel thread = threadStore.findThread(persisted.userId(), persisted.threadId()).orElse(null);
            if (thread == null) {
                continue;
            }
            ThreadSlot slot = slots.computeIfAbsent(thread.threadId(), ignored -> new ThreadSlot());
            synchronized (slot) {
                if (persisted.status() == AgentTurnStatusEnum.ACTIVE) {
                    AgentTurnModel failed = persisted.terminal(AgentTurnStatusEnum.FAILED,
                            "RUNTIME_RESTARTED", clock.instant());
                    turns.updateTurn(failed);
                    appendItem(failed, AgentItemTypeEnum.ERROR, "运行时重启，未恢复执行中的 Turn");
                    publishTurn(failed);
                    continue;
                }
                boolean present = slot.queue.stream()
                        .anyMatch(queued -> queued.turn.turnId().equals(persisted.turnId()));
                if (present) {
                    continue;
                }
                if (slot.queue.size() >= maxPendingPerThread || pendingGlobal.get() >= maxPendingGlobal) {
                    AgentTurnModel failed = persisted.terminal(AgentTurnStatusEnum.FAILED,
                            "RUNTIME_QUEUE_OVERFLOW", clock.instant());
                    turns.updateTurn(failed);
                    appendItem(failed, AgentItemTypeEnum.ERROR, "运行时重启后排队容量不足");
                    publishTurn(failed);
                    continue;
                }
                QueuedTurn queued = new QueuedTurn(
                        persisted, Map.of(), new AtomicBoolean(false), new AtomicBoolean(false));
                slot.queue.addLast(queued);
                pendingGlobal.incrementAndGet();
                publishTurn(persisted);
                scheduleQueueTimeout(thread.threadId(), queued);
                schedule(slot, thread);
            }
        }
    }

    public List<AgentTurnModel> listTurns(String userId, String threadId) {
        ownedThread(userId, threadId);
        return turns.listTurns(userId, threadId);
    }

    public AgentThreadModel updateThread(String userId, String threadId, String title, boolean archive) {
        return threads.update(userId, threadId, title, archive);
    }

    public AgentTurnModel submitTurn(String userId, String threadId, String requestId, String message) {
        AgentThreadModel thread = ownedThread(userId, threadId);
        if (thread.status() == AgentThreadStatusEnum.ARCHIVED) {
            throw new AgentThreadConflictException("THREAD_ARCHIVED", "归档 Thread 不接受新消息");
        }
        requireText(requestId, "clientRequestId");
        requireText(message, "message");
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(userId, requestId);
        if (duplicate.isPresent()) return duplicate.get();
        if (questions.findOpenQuestion(userId, threadId).isPresent()) {
            throw new AgentThreadConflictException("THREAD_AWAITING_ANSWER", "当前 Thread 正在等待 QuestionCard 回答");
        }
        ThreadSlot slot = slots.computeIfAbsent(threadId, ignored -> new ThreadSlot());
        QueuedTurn queued;
        synchronized (slot) {
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
            turns.createTurn(turn);
            appendItem(turn, AgentItemTypeEnum.USER_MESSAGE, message);
            queued = new QueuedTurn(turn, Map.of(), new AtomicBoolean(false), new AtomicBoolean(false));
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
        AgentWorkflowQuestionModel question = questions.findOpenQuestion(userId, threadId)
                .orElseThrow(() -> new AgentThreadConflictException("QUESTION_NOT_OPEN", "QuestionCard 已关闭"));
        requireText(requestId, "clientRequestId");
        Optional<AgentTurnModel> duplicate = turns.findTurnByRequest(userId, requestId);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        if (!question.runId().equals(runId) || !question.questionId().equals(questionId)
                || !question.checkpointId().equals(checkpointId) || question.version() != expectedVersion) {
            throw new AgentThreadConflictException("WORKFLOW_VERSION_CONFLICT", "QuestionCard 检查点或版本已变化");
        }
        if (answers == null || answers.isEmpty()) {
            throw new IllegalArgumentException("answers 不能为空");
        }
        ThreadSlot slot = slots.computeIfAbsent(threadId, ignored -> new ThreadSlot());
        synchronized (slot) {
            if (slot.queue.size() >= maxPendingPerThread || pendingGlobal.get() >= maxPendingGlobal) {
                throw new AgentThreadConflictException("AGENT_QUEUE_FULL", "回答请求无法入队");
            }
            AgentTurnModel turn = new AgentTurnModel(
                    UUID.randomUUID().toString(), threadId, userId, requestId,
                    "QuestionCard 回答：" + answers, AgentTurnStatusEnum.QUEUED,
                    slot.queue.size() + 1, runId, null, clock.instant(), null, null
            );
            turns.createTurn(turn);
            appendItem(turn, AgentItemTypeEnum.WORKFLOW_ANSWER, answers.toString());
            QueuedTurn queued = new QueuedTurn(turn, Map.copyOf(answers), new AtomicBoolean(false), new AtomicBoolean(false));
            slot.queue.addLast(queued);
            pendingGlobal.incrementAndGet();
            publishTurn(turn);
            scheduleQueueTimeout(thread.threadId(), queued);
            schedule(slot, thread);
            return turn;
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
        AgentWorkflowQuestionModel question = questions.findOpenQuestionByRun(userId, runId)
                .orElseThrow(() -> new AgentThreadConflictException("QUESTION_NOT_OPEN", "QuestionCard 已关闭"));
        return answerQuestion(userId, question.threadId(), requestId, runId, questionId,
                checkpointId, expectedVersion, answers);
    }

    public boolean cancel(String userId, String turnId) {
        Optional<AgentTurnModel> found = threadStore.listThreads(userId).stream()
                .flatMap(thread -> turns.listTurns(userId, thread.threadId()).stream())
                .filter(turn -> turn.turnId().equals(turnId)).findFirst();
        if (found.isEmpty()) return false;
        AgentTurnModel turn = found.get();
        ThreadSlot slot = slots.computeIfAbsent(turn.threadId(), ignored -> new ThreadSlot());
        synchronized (slot) {
            for (QueuedTurn queued : slot.queue) {
                if (queued.turn.turnId().equals(turnId)) {
                    queued.cancelled.set(true);
                    slot.queue.remove(queued);
                    pendingGlobal.decrementAndGet();
                    AgentTurnModel cancelled = queued.turn.terminal(AgentTurnStatusEnum.CANCELLED, "CLIENT_CANCELLED", clock.instant());
                    turns.updateTurn(cancelled);
                    appendItem(cancelled, AgentItemTypeEnum.EXECUTION_EVENT, "排队 Turn 已取消");
                    publishTurn(cancelled);
                    return true;
                }
            }
            if (slot.active != null && slot.active.turn.turnId().equals(turnId)) {
                slot.active.cancelled.set(true);
                return true;
            }
        }
        return turn.status() == AgentTurnStatusEnum.CANCELLED;
    }

    public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence) {
        ownedThread(userId, threadId);
        return items.listItems(userId, threadId, afterSequence, 500);
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
        Instant started = clock.instant();
        AgentTurnModel active = turn.active(started);
        turns.updateTurn(active);
        publishTurn(active);
        ScheduledFuture<?> timeout = scheduler.schedule(
                () -> {
                    execution.timedOut.set(true);
                    execution.cancelled.set(true);
                }, turnTimeout.toMillis(), TimeUnit.MILLISECONDS
        );
        try {
            List<AgentItemModel> context = contextAssembler.assemble(thread);
            AgentTurnCoordinator.AgentCoordinatorResult result = coordinator.run(thread, active, context, execution.answer);
            if (execution.cancelled.get()) {
                finish(active, execution.timedOut.get() ? AgentTurnStatusEnum.TIMED_OUT : AgentTurnStatusEnum.CANCELLED,
                        execution.timedOut.get() ? "TURN_TIMEOUT" : "CLIENT_CANCELLED");
                return;
            }
            for (AgentTurnCoordinator.AgentItemDraft draft : result.items()) {
                appendItem(active, parseType(draft.type()), draft.payload());
            }
            if (result.question() != null) {
                appendItem(active, AgentItemTypeEnum.WORKFLOW_STARTED,
                        result.workflowRunId() == null ? "workflow started" : result.workflowRunId());
                appendItem(active, AgentItemTypeEnum.WORKFLOW_QUESTION, questionPayload(result.question()));
                questions.saveQuestion(result.question());
                AgentTurnModel waiting = active.workflow(result.workflowRunId(), AgentTurnStatusEnum.WAITING_USER_INPUT);
                turns.updateTurn(waiting);
                publishTurn(waiting);
                return;
            }
            if (!result.assistantMessage().isBlank()) {
                appendItem(active, AgentItemTypeEnum.ASSISTANT_MESSAGE, result.assistantMessage());
            }
            finish(active, AgentTurnStatusEnum.COMPLETED, null);
        } catch (RuntimeException failure) {
            appendItem(active, AgentItemTypeEnum.ERROR, failure.getMessage() == null ? "Agent 执行失败" : failure.getMessage());
            finish(active, execution.timedOut.get() ? AgentTurnStatusEnum.TIMED_OUT : AgentTurnStatusEnum.FAILED,
                    execution.timedOut.get() ? "TURN_TIMEOUT" : "AGENT_EXECUTION_FAILED");
        } finally {
            timeout.cancel(false);
            slot.active = null;
        }
    }

    private void finish(AgentTurnModel active, AgentTurnStatusEnum status, String code) {
        AgentTurnModel terminal = active.terminal(status, code, clock.instant());
        turns.updateTurn(terminal);
        publishTurn(terminal);
    }

    private void appendItem(AgentTurnModel turn, AgentItemTypeEnum type, String payload) {
        String boundedPayload = type == AgentItemTypeEnum.TOOL_RESULT && payload != null
                && payload.length() > toolResultMaxCharacters
                ? payload.substring(0, toolResultMaxCharacters) + "…[TOOL_RESULT_TRUNCATED]"
                : payload;
        AgentItemModel item = new AgentItemModel(
                UUID.randomUUID().toString(), turn.threadId(), turn.turnId(), 0, type, boundedPayload, clock.instant()
        );
        long sequence = items.appendItem(item);
        events.itemCreated(new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt()));
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
            AgentTurnModel timedOut = target.turn.terminal(AgentTurnStatusEnum.TIMED_OUT,
                    "QUEUE_WAIT_TIMEOUT", clock.instant());
            turns.updateTurn(timedOut);
            appendItem(timedOut, AgentItemTypeEnum.EXECUTION_EVENT, "排队等待超时");
            publishTurn(timedOut);
        }
    }

    private void publishTurn(AgentTurnModel turn) {
        events.turnUpdated(turn);
    }

    private String questionPayload(AgentWorkflowQuestionModel question) {
        return "{\"runId\":\"" + escape(question.runId()) + "\",\"questionId\":\""
                + escape(question.questionId()) + "\",\"checkpointId\":\"" + escape(question.checkpointId())
                + "\",\"version\":" + question.version() + ",\"title\":\"" + escape(question.title())
                + "\",\"prompt\":\"" + escape(question.prompt()) + "\",\"fields\":" + question.fieldsJson() + "}";
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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

    private static final class ThreadSlot {
        private final Deque<QueuedTurn> queue = new ArrayDeque<>();
        private boolean running;
        private QueuedTurn active;
    }

    private record QueuedTurn(
            AgentTurnModel turn,
            Map<String, String> answer,
            AtomicBoolean cancelled,
            AtomicBoolean timedOut
    ) {
    }
}
