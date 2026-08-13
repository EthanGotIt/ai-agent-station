package cn.ethan.core.agent.service;

import cn.ethan.core.agent.exception.SessionQueueException;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.model.QueuedExecutionModel;
import cn.ethan.core.agent.model.RequestHandleModel;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Session 执行队列管理器：按用户与 Session 维持有界 FIFO 和排队超时。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class SessionExecutionQueueManager {

    private static final String SESSION_QUEUE_FULL = "SESSION_QUEUE_FULL";
    private static final String GLOBAL_QUEUE_FULL = "GLOBAL_QUEUE_FULL";
    private static final String QUEUE_WAIT_TIMEOUT = "QUEUE_WAIT_TIMEOUT";

    private final Object monitor = new Object();
    private final Map<String, SessionQueue> sessionQueues = new HashMap<>();
    private final Map<String, QueueEntry> entriesByRequestId = new HashMap<>();
    private final int maxPendingPerSession;
    private final int maxPendingGlobal;
    private final Duration waitTimeout;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;

    private int pendingGlobal;

    public SessionExecutionQueueManager(
            int maxPendingPerSession,
            int maxPendingGlobal,
            Duration waitTimeout,
            Executor executor,
            ScheduledExecutorService scheduler
    ) {
        if (maxPendingPerSession < 1) {
            throw new IllegalArgumentException("maxPendingPerSession must be positive");
        }
        if (maxPendingGlobal < maxPendingPerSession) {
            throw new IllegalArgumentException(
                    "maxPendingGlobal must not be less than maxPendingPerSession"
            );
        }
        if (waitTimeout == null || waitTimeout.isZero() || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("waitTimeout must be positive");
        }
        this.maxPendingPerSession = maxPendingPerSession;
        this.maxPendingGlobal = maxPendingGlobal;
        this.waitTimeout = waitTimeout;
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
    }

    public QueuedExecutionModel submit(
            AgentRequestModel request,
            RequestHandleModel handle,
            Callable<AgentResponseModel> execution
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(execution, "execution must not be null");

        QueueEntry entry = new QueueEntry(request, handle, execution);
        QueueEntry dispatchEntry = null;
        QueueEntry nextEntryAfterSchedulingFailure = null;
        SessionQueueException schedulingFailure = null;
        synchronized (monitor) {
            String sessionKey = sessionKey(handle.userId(), handle.sessionId());
            SessionQueue sessionQueue = sessionQueues.computeIfAbsent(
                    sessionKey,
                    ignoredKey -> new SessionQueue(sessionKey)
            );
            if (pendingCount(sessionQueue) >= maxPendingPerSession) {
                removeEmptySession(sessionQueue);
                throw new SessionQueueException(
                        SESSION_QUEUE_FULL,
                        "Session 排队请求数量已达到上限",
                        relatedRequestId(sessionQueue)
                );
            }
            if (pendingGlobal >= maxPendingGlobal) {
                removeEmptySession(sessionQueue);
                throw new SessionQueueException(
                        GLOBAL_QUEUE_FULL,
                        "全局排队请求数量已达到上限",
                        null
                );
            }

            pendingGlobal++;
            entriesByRequestId.put(handle.requestId(), entry);
            if (sessionQueue.current == null) {
                sessionQueue.current = entry;
                dispatchEntry = entry;
            } else {
                sessionQueue.waiting.addLast(entry);
            }
            try {
                entry.timeoutFuture = scheduler.schedule(
                        () -> expire(handle.requestId()),
                        waitTimeout.toMillis(),
                        TimeUnit.MILLISECONDS
                );
            } catch (RuntimeException rejected) {
                nextEntryAfterSchedulingFailure = removeWaitingEntry(sessionQueue, entry);
                schedulingFailure = new SessionQueueException(
                        GLOBAL_QUEUE_FULL,
                        "请求调度器暂时无法接收新的请求",
                        null
                );
            }
        }

        if (schedulingFailure != null) {
            entry.handle.token().cancel();
            entry.completion.completeExceptionally(schedulingFailure);
            if (nextEntryAfterSchedulingFailure != null) {
                dispatch(nextEntryAfterSchedulingFailure);
            }
            throw schedulingFailure;
        }

        if (dispatchEntry != null) {
            dispatch(dispatchEntry, true);
        }
        return new QueuedExecutionModel(handle, entry.completion);
    }

    public boolean cancelWaiting(String requestId, String userId) {
        QueueEntry cancelledEntry;
        QueueEntry nextEntry;
        synchronized (monitor) {
            QueueEntry entry = entriesByRequestId.get(requestId);
            if (entry == null
                    || !entry.handle.userId().equals(userId)
                    || entry.started) {
                return false;
            }
            SessionQueue sessionQueue = sessionQueues.get(
                    sessionKey(entry.handle.userId(), entry.handle.sessionId())
            );
            if (sessionQueue == null) {
                return false;
            }
            cancelledEntry = entry;
            nextEntry = removeWaitingEntry(sessionQueue, entry);
        }

        cancelledEntry.handle.token().cancel();
        cancelledEntry.completion.complete(AgentResponseModel.cancelled(cancelledEntry.request));
        if (nextEntry != null) {
            dispatch(nextEntry);
        }
        return true;
    }

    public int pendingCount() {
        synchronized (monitor) {
            return pendingGlobal;
        }
    }

    private void dispatch(QueueEntry entry) {
        dispatch(entry, false);
    }

    private void dispatch(QueueEntry entry, boolean propagateRejection) {
        try {
            executor.execute(() -> runEntry(entry));
        } catch (RuntimeException rejectedExecution) {
            SessionQueueException queueFailure = new SessionQueueException(
                    GLOBAL_QUEUE_FULL,
                    "执行线程池暂时无法接收新的请求",
                    null
            );
            failBeforeStart(
                    entry,
                    queueFailure
            );
            if (propagateRejection) {
                throw queueFailure;
            }
        }
    }

    private void runEntry(QueueEntry entry) {
        if (!markStarted(entry)) {
            return;
        }

        AgentResponseModel response = null;
        Throwable failure = null;
        try {
            response = entry.execution.call();
        } catch (Throwable executionFailure) {
            failure = executionFailure;
        } finally {
            QueueEntry nextEntry = finish(entry);
            if (failure == null) {
                entry.completion.complete(response);
            } else {
                entry.completion.completeExceptionally(failure);
            }
            if (nextEntry != null) {
                dispatch(nextEntry);
            }
        }
    }

    private boolean markStarted(QueueEntry entry) {
        synchronized (monitor) {
            SessionQueue sessionQueue = sessionQueues.get(
                    sessionKey(entry.handle.userId(), entry.handle.sessionId())
            );
            if (sessionQueue == null
                    || sessionQueue.current != entry
                    || !entriesByRequestId.containsKey(entry.handle.requestId())) {
                return false;
            }
            entry.started = true;
            pendingGlobal--;
            cancelTimeout(entry);
            return true;
        }
    }

    private QueueEntry finish(QueueEntry entry) {
        synchronized (monitor) {
            String sessionKey = sessionKey(entry.handle.userId(), entry.handle.sessionId());
            SessionQueue sessionQueue = sessionQueues.get(sessionKey);
            entriesByRequestId.remove(entry.handle.requestId(), entry);
            if (sessionQueue == null || sessionQueue.current != entry) {
                return null;
            }
            QueueEntry nextEntry = sessionQueue.waiting.pollFirst();
            sessionQueue.current = nextEntry;
            if (nextEntry == null) {
                sessionQueues.remove(sessionKey, sessionQueue);
            }
            return nextEntry;
        }
    }

    private void expire(String requestId) {
        QueueEntry expiredEntry;
        QueueEntry nextEntry;
        synchronized (monitor) {
            QueueEntry entry = entriesByRequestId.get(requestId);
            if (entry == null || entry.started) {
                return;
            }
            SessionQueue sessionQueue = sessionQueues.get(
                    sessionKey(entry.handle.userId(), entry.handle.sessionId())
            );
            if (sessionQueue == null) {
                return;
            }
            expiredEntry = entry;
            nextEntry = removeWaitingEntry(sessionQueue, entry);
        }

        expiredEntry.handle.token().cancel();
        expiredEntry.completion.completeExceptionally(new SessionQueueException(
                QUEUE_WAIT_TIMEOUT,
                "请求排队等待时间超过上限",
                null
        ));
        if (nextEntry != null) {
            dispatch(nextEntry);
        }
    }

    private void failBeforeStart(QueueEntry entry, RuntimeException failure) {
        QueueEntry nextEntry;
        synchronized (monitor) {
            SessionQueue sessionQueue = sessionQueues.get(
                    sessionKey(entry.handle.userId(), entry.handle.sessionId())
            );
            if (sessionQueue == null || entry.started) {
                return;
            }
            nextEntry = removeWaitingEntry(sessionQueue, entry);
        }
        entry.handle.token().cancel();
        entry.completion.completeExceptionally(failure);
        if (nextEntry != null) {
            dispatch(nextEntry);
        }
    }

    private QueueEntry removeWaitingEntry(SessionQueue sessionQueue, QueueEntry entry) {
        QueueEntry nextEntry = null;
        if (sessionQueue.current == entry) {
            nextEntry = sessionQueue.waiting.pollFirst();
            sessionQueue.current = nextEntry;
        } else {
            sessionQueue.waiting.remove(entry);
        }
        entriesByRequestId.remove(entry.handle.requestId(), entry);
        pendingGlobal--;
        cancelTimeout(entry);
        if (sessionQueue.current == null && sessionQueue.waiting.isEmpty()) {
            sessionQueues.remove(sessionQueue.sessionKey, sessionQueue);
        }
        return nextEntry;
    }

    private void cancelTimeout(QueueEntry entry) {
        ScheduledFuture<?> timeoutFuture = entry.timeoutFuture;
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
        }
    }

    private int pendingCount(SessionQueue sessionQueue) {
        int count = sessionQueue.waiting.size();
        if (sessionQueue.current != null && !sessionQueue.current.started) {
            count++;
        }
        return count;
    }

    private String relatedRequestId(SessionQueue sessionQueue) {
        if (sessionQueue.current != null) {
            return sessionQueue.current.handle.requestId();
        }
        QueueEntry firstWaiting = sessionQueue.waiting.peekFirst();
        return firstWaiting == null ? null : firstWaiting.handle.requestId();
    }

    private void removeEmptySession(SessionQueue sessionQueue) {
        if (sessionQueue.current == null && sessionQueue.waiting.isEmpty()) {
            sessionQueues.remove(sessionQueue.sessionKey, sessionQueue);
        }
    }

    private String sessionKey(String userId, String sessionId) {
        return userId + "\u0000" + sessionId;
    }

    private static final class SessionQueue {

        private final String sessionKey;
        private final ArrayDeque<QueueEntry> waiting = new ArrayDeque<>();

        private QueueEntry current;

        private SessionQueue(String sessionKey) {
            this.sessionKey = sessionKey;
        }
    }

    private static final class QueueEntry {

        private final AgentRequestModel request;
        private final RequestHandleModel handle;
        private final Callable<AgentResponseModel> execution;
        private final CompletableFuture<AgentResponseModel> completion = new CompletableFuture<>();

        private ScheduledFuture<?> timeoutFuture;
        private boolean started;

        private QueueEntry(
                AgentRequestModel request,
                RequestHandleModel handle,
                Callable<AgentResponseModel> execution
        ) {
            this.request = request;
            this.handle = handle;
            this.execution = execution;
        }
    }
}
