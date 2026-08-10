package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.RequestLifecycleStateEnum;
import cn.ethan.core.agent.exception.RequestLifecycleException;
import cn.ethan.core.agent.model.RequestHandleModel;
import cn.ethan.core.agent.support.CancellationToken;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 请求生命周期管理器：维护请求身份、状态转换和用户级取消归属。
 *
 * @author ethan
 * @date 2026-08-06
 */
public final class RequestLifecycleManager {

    private record Entry(
            RequestHandleModel handle,
            RequestLifecycleStateEnum state,
            Instant terminalAt
    ) {
    }

    private final Map<String, Entry> requests = new ConcurrentHashMap<>();
    private final Duration terminalTtl;
    private final Clock clock;

    public RequestLifecycleManager(Duration terminalTtl) {
        this(terminalTtl, Clock.systemUTC());
    }

    public RequestLifecycleManager(Duration terminalTtl, Clock clock) {
        this.terminalTtl = Objects.requireNonNull(terminalTtl, "terminalTtl must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (terminalTtl.isZero() || terminalTtl.isNegative()) {
            throw new IllegalArgumentException("terminalTtl must be positive");
        }
    }

    public synchronized RequestHandleModel prepare(
            String requestId,
            String userId,
            String sessionId
    ) {
        cleanup();
        if (requests.containsKey(requestId)) {
            throw new RequestLifecycleException(
                    "REQUEST_ID_CONFLICT",
                    "requestId 在保留期内已存在",
                    requestId
            );
        }

        RequestHandleModel handle = new RequestHandleModel(
                requestId,
                userId,
                sessionId,
                new CancellationToken()
        );
        requests.put(requestId, new Entry(
                handle,
                RequestLifecycleStateEnum.PREPARED,
                null
        ));
        return handle;
    }

    public synchronized void markQueued(String requestId) {
        transition(requestId, RequestLifecycleStateEnum.PREPARED, RequestLifecycleStateEnum.QUEUED);
    }

    public synchronized void activate(String requestId) {
        Entry entry = requests.get(requestId);
        if (entry == null) {
            throw new IllegalStateException("request lifecycle entry does not exist");
        }
        if (entry.handle().token().isCancelled()
                || entry.state() == RequestLifecycleStateEnum.CANCELLING
                || entry.state() == RequestLifecycleStateEnum.CANCELLED) {
            throw new CancellationException("request cancelled before activation");
        }
        transition(requestId, RequestLifecycleStateEnum.QUEUED, RequestLifecycleStateEnum.ACTIVE);
    }

    public synchronized Optional<RequestHandleModel> findOwned(
            String requestId,
            String userId
    ) {
        Entry entry = requests.get(requestId);
        if (entry == null || userId == null || !userId.equals(entry.handle().userId())) {
            return Optional.empty();
        }
        return Optional.of(entry.handle());
    }

    public synchronized boolean cancelActive(String requestId, String userId) {
        Entry entry = requests.get(requestId);
        if (entry == null || userId == null || !userId.equals(entry.handle().userId())) {
            return false;
        }
        if (entry.state() == RequestLifecycleStateEnum.CANCELLING
                || entry.state() == RequestLifecycleStateEnum.CANCELLED) {
            return true;
        }
        if (entry.state() != RequestLifecycleStateEnum.ACTIVE
                && entry.state() != RequestLifecycleStateEnum.QUEUED
                && entry.state() != RequestLifecycleStateEnum.PREPARED) {
            return false;
        }
        entry.handle().token().cancel();
        requests.put(requestId, new Entry(
                entry.handle(),
                RequestLifecycleStateEnum.CANCELLING,
                null
        ));
        return true;
    }

    public synchronized void complete(String requestId) {
        transitionToTerminal(requestId, RequestLifecycleStateEnum.COMPLETED);
    }

    public synchronized void fail(String requestId) {
        transitionToTerminal(requestId, RequestLifecycleStateEnum.FAILED);
    }

    public synchronized void markCancelled(String requestId) {
        transitionToTerminal(requestId, RequestLifecycleStateEnum.CANCELLED);
    }

    public RequestLifecycleStateEnum state(String requestId) {
        Entry entry = requests.get(requestId);
        return entry == null ? null : entry.state();
    }

    public synchronized void cleanup() {
        Instant cutoff = Instant.now(clock).minus(terminalTtl);
        requests.entrySet().removeIf(entry -> {
            Instant terminalAt = entry.getValue().terminalAt();
            return terminalAt != null && terminalAt.isBefore(cutoff);
        });
    }

    private void transition(
            String requestId,
            RequestLifecycleStateEnum expected,
            RequestLifecycleStateEnum target
    ) {
        Entry entry = requests.get(requestId);
        if (entry == null || entry.state() != expected) {
            RequestLifecycleStateEnum current = entry == null ? null : entry.state();
            throw new IllegalStateException(
                    "request lifecycle transition is invalid: " + current + " -> " + target
            );
        }
        requests.put(requestId, new Entry(entry.handle(), target, null));
    }

    private void transitionToTerminal(String requestId, RequestLifecycleStateEnum state) {
        Entry entry = requests.get(requestId);
        if (entry == null) {
            return;
        }
        requests.put(requestId, new Entry(entry.handle(), state, Instant.now(clock)));
    }
}
