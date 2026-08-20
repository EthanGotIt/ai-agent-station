package cn.ethan.app.agent.api;

import cn.ethan.app.bootstrap.AgentRuntimeProperties;
import cn.ethan.app.agent.stream.AgentThreadEventStream;
import cn.ethan.core.agent.event.AgentThreadEventSubscription;
import cn.ethan.core.agent.thread.AgentThreadService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 类型职责：恢复 Item 游标后订阅 Thread 实时事件，并维护 SSE 心跳生命周期。
 *
 * @author ethan
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentThreadEventController {

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
        AgentThreadEventStream stream = new AgentThreadEventStream(
                threadId, afterSequence, event -> send(emitter, event));
        AutoCloseable subscription = events.subscribe(stream::accept);
        stream.attachSubscription(subscription);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);
        emitter.onError(failure -> stream.close());
        stream.replay(
                after -> threads.listItems(userId, threadId, after, 500),
                () -> new AgentThreadEventDto("ready-" + UUID.randomUUID(), threadId, null, null,
                        "ready", "{\"afterSequence\":" + stream.currentCursor() + "}",
                        stream.currentCursor(), clock.instant()));
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> stream.publishControl(
                        new AgentThreadEventDto("heartbeat-" + UUID.randomUUID(), threadId, null, null,
                                "heartbeat", "{\"afterSequence\":" + stream.currentCursor() + "}",
                                stream.currentCursor(), clock.instant())),
                properties.heartbeatInterval().toSeconds(), properties.heartbeatInterval().toSeconds(), TimeUnit.SECONDS);
        stream.attachHeartbeat(heartbeat);
        return emitter;
    }

    private void send(SseEmitter emitter, AgentThreadEventDto event) {
        try {
            emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event));
        } catch (IOException failure) {
            throw new IllegalStateException("SSE event send failed", failure);
        }
    }
}
