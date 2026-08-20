package cn.ethan.app.agent.api;

import cn.ethan.app.bootstrap.AgentRuntimeProperties;
import cn.ethan.core.agent.event.AgentThreadEventSubscription;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类型职责：恢复 Item 游标后订阅 Thread 实时事件，并维护 SSE 心跳生命周期。
 *
 * @author ethan
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentThreadEventController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentThreadEventController.class);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final AgentThreadService threads;
    private final AgentThreadEventSubscription events;
    private final AgentRuntimeProperties properties;
    private final AgentUserContext userContext;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;

    public AgentThreadEventController(
            AgentThreadService threads,
            AgentThreadEventSubscription events,
            AgentRuntimeProperties properties,
            AgentUserContext userContext,
            Clock clock,
            ScheduledExecutorService scheduler
    ) {
        this.threads = threads;
        this.events = events;
        this.properties = properties;
        this.userContext = userContext;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    @GetMapping(value = "/threads/{threadId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String threadId,
            @RequestParam(defaultValue = "0") long afterSequence,
            HttpServletRequest request
    ) {
        String userId = userContext.currentUserId(request);
        threads.get(userId, threadId);
        SseEmitter emitter = new SseEmitter(properties.streamTimeoutMillis());
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicLong cursor = new AtomicLong(Math.max(0, afterSequence));
        AutoCloseable subscription = events.subscribe(event -> {
            if (!open.get() || !event.threadId().equals(threadId)) return;
            if (event.sequence() >= 0 && event.sequence() <= cursor.get()) return;
            send(emitter, AgentThreadEventDto.from(event), open);
            if (event.sequence() >= 0) cursor.accumulateAndGet(event.sequence(), Math::max);
        });
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> send(emitter,
                new AgentThreadEventDto("heartbeat-" + UUID.randomUUID(), threadId, null, null,
                        "heartbeat", "{\"afterSequence\":" + cursor.get() + "}", cursor.get(), clock.instant()), open),
                HEARTBEAT_INTERVAL.toSeconds(), HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
        emitter.onCompletion(() -> close(open, subscription, heartbeat));
        emitter.onTimeout(() -> close(open, subscription, heartbeat));
        emitter.onError(failure -> close(open, subscription, heartbeat));
        for (AgentItemModel item : threads.listItems(userId, threadId, cursor.get(), 500)) {
            send(emitter, new AgentThreadEventDto(item.itemId(), threadId, item.turnId(), item.itemId(),
                    "item." + item.type().name().toLowerCase(), item.payloadJson(), item.sequence(), item.createdAt()), open);
            cursor.set(item.sequence());
        }
        send(emitter, new AgentThreadEventDto("ready-" + UUID.randomUUID(), threadId, null, null,
                "ready", "{\"afterSequence\":" + cursor.get() + "}", cursor.get(), clock.instant()), open);
        return emitter;
    }

    private void send(SseEmitter emitter, AgentThreadEventDto event, AtomicBoolean open) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
        } catch (IOException failure) {
            close(open, null, null);
        }
    }

    private void close(AtomicBoolean open, AutoCloseable subscription, ScheduledFuture<?> heartbeat) {
        if (!open.compareAndSet(true, false)) return;
        if (heartbeat != null) heartbeat.cancel(false);
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception failure) {
            LOGGER.debug("SSE subscription close failed, errorType={}", failure.getClass().getSimpleName());
        }
    }
}
