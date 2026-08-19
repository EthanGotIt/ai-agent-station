package cn.ethan.controller;

import cn.ethan.config.AgentRuntimeProperties;
import cn.ethan.config.AgentUserContext;
import cn.ethan.core.agent.thread.model.AgentItemModel;
import cn.ethan.core.agent.action.model.ExternalActionCommandModel;
import cn.ethan.core.agent.action.enums.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.port.ExternalActionCommandStore;
import cn.ethan.core.agent.thread.exception.AgentThreadConflictException;
import cn.ethan.core.agent.thread.exception.AgentThreadNotFoundException;
import cn.ethan.core.agent.thread.service.AgentThreadRuntimeService;
import cn.ethan.core.agent.thread.service.AgentThreadService;
import cn.ethan.core.agent.thread.support.InMemoryAgentThreadEventGateway;
import cn.ethan.dto.AgentCancelResponseDto;
import cn.ethan.dto.AgentItemDto;
import cn.ethan.dto.AgentItemPageResponseDto;
import cn.ethan.dto.AgentQuestionAnswerRequestDto;
import cn.ethan.dto.AgentRetryResponseDto;
import cn.ethan.dto.AgentThreadCreateRequestDto;
import cn.ethan.dto.AgentThreadDto;
import cn.ethan.dto.AgentThreadEventDto;
import cn.ethan.dto.AgentThreadPageResponseDto;
import cn.ethan.dto.AgentThreadUpdateRequestDto;
import cn.ethan.dto.AgentTurnAcceptedResponseDto;
import cn.ethan.dto.AgentTurnSubmitRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 类型职责：提供唯一 `/api/agent` Thread、Turn、Item 和 SSE HTTP 契约。
 *
 * @author ethan
 * @date 2026-08-19
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentThreadController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentThreadController.class);

    private final AgentThreadService threads;
    private final AgentThreadRuntimeService runtime;
    private final InMemoryAgentThreadEventGateway events;
    private final AgentRuntimeProperties properties;
    private final AgentUserContext userContext;
    private final ExternalActionCommandStore actions;
    private final Clock clock;

    public AgentThreadController(
            AgentThreadService threads,
            AgentThreadRuntimeService runtime,
            InMemoryAgentThreadEventGateway events,
            AgentRuntimeProperties properties,
            AgentUserContext userContext,
            ExternalActionCommandStore actions,
            Clock clock
    ) {
        this.threads = threads;
        this.runtime = runtime;
        this.events = events;
        this.properties = properties;
        this.userContext = userContext;
        this.actions = actions;
        this.clock = clock;
    }

    @PostMapping("/threads")
    public AgentThreadDto create(
            @Valid @RequestBody(required = false) AgentThreadCreateRequestDto body,
            HttpServletRequest request
    ) {
        AgentThreadCreateRequestDto input = body == null
                ? new AgentThreadCreateRequestDto(null, null, null)
                : body;
        return AgentThreadDto.from(threads.create(userContext.currentUserId(request),
                input.title(), input.contextType(), input.contextId()));
    }

    @GetMapping("/threads")
    public AgentThreadPageResponseDto list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest request
    ) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<AgentThreadDto> all = threads.list(userContext.currentUserId(request)).stream()
                .map(AgentThreadDto::from).toList();
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        return new AgentThreadPageResponseDto(all.subList(from, to), safePage, safeSize, all.size());
    }

    @GetMapping("/threads/{threadId}")
    public AgentThreadDto get(@PathVariable String threadId, HttpServletRequest request) {
        return AgentThreadDto.from(threads.get(userContext.currentUserId(request), threadId));
    }

    @PatchMapping("/threads/{threadId}")
    public AgentThreadDto update(
            @PathVariable String threadId,
            @Valid @RequestBody AgentThreadUpdateRequestDto body,
            HttpServletRequest request
    ) {
        return AgentThreadDto.from(threads.update(userContext.currentUserId(request), threadId,
                body.title(), body.archive()));
    }

    @GetMapping("/threads/{threadId}/items")
    public AgentItemPageResponseDto items(
            @PathVariable String threadId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest request
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<AgentItemModel> items = runtime.listItems(userContext.currentUserId(request), threadId, afterSequence);
        List<AgentItemModel> page = items.stream().limit(safeLimit).toList();
        long next = page.isEmpty() ? Math.max(0, afterSequence) : page.get(page.size() - 1).sequence();
        return new AgentItemPageResponseDto(page.stream().map(AgentItemDto::from).toList(),
                Math.max(0, afterSequence), next, page.size() == safeLimit);
    }

    @PostMapping("/threads/{threadId}/turns")
    public ResponseEntity<AgentTurnAcceptedResponseDto> submit(
            @PathVariable String threadId,
            @Valid @RequestBody AgentTurnSubmitRequestDto body,
            HttpServletRequest request
    ) {
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(
                runtime.submitTurn(userContext.currentUserId(request), threadId, body.clientRequestId(), body.message())
        ));
    }

    @PostMapping("/turns/{turnId}/cancel")
    public ResponseEntity<AgentCancelResponseDto> cancel(
            @PathVariable String turnId,
            HttpServletRequest request
    ) {
        String userId = userContext.currentUserId(request);
        boolean cancelled = runtime.cancel(userId, turnId);
        return cancelled
                ? ResponseEntity.ok(new AgentCancelResponseDto(turnId, true))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/workflow-runs/{runId}/questions/{questionId}/answers")
    public ResponseEntity<AgentTurnAcceptedResponseDto> answer(
            @PathVariable String runId,
            @PathVariable String questionId,
            @Valid @RequestBody AgentQuestionAnswerRequestDto body,
            HttpServletRequest request
    ) {
        if (!runId.equals(body.runId()) || !questionId.equals(body.questionId())) {
            throw new IllegalArgumentException("QuestionCard 路径参数与请求体不一致");
        }
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(runtime.answerQuestion(
                userContext.currentUserId(request), body.clientRequestId(), runId, questionId,
                body.checkpointId(), body.expectedVersion(), body.answers()
        )));
    }

    @PostMapping("/workflow-runs/{runId}/retry")
    public AgentRetryResponseDto retry(
            @PathVariable String runId,
            HttpServletRequest request
    ) {
        String userId = userContext.currentUserId(request);
        ExternalActionCommandModel command = actions.findByRunId(userId, runId)
                .orElseThrow(() -> new AgentThreadNotFoundException(runId));
        if (command.status() != ExternalActionStatusEnum.MANUAL_RETRY_REQUIRED) {
            throw new AgentThreadConflictException("ACTION_NOT_RETRYABLE", "外部动作当前不需要人工重试");
        }
        ExternalActionCommandModel retried = command.manualRetry(clock.instant());
        actions.update(retried);
        return new AgentRetryResponseDto(runId, retried.commandId(), retried.status().name(), retried.idempotencyKey());
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
        try {
            AutoCloseable subscription = events.subscribe(event -> {
                if (!open.get() || !event.threadId().equals(threadId)) return;
                if (event.sequence() >= 0 && event.sequence() <= cursor.get()) return;
                send(emitter, AgentThreadEventDto.from(event), open);
                if (event.sequence() >= 0) cursor.accumulateAndGet(event.sequence(), Math::max);
            });
            emitter.onCompletion(() -> close(open, subscription));
            emitter.onTimeout(() -> close(open, subscription));
            emitter.onError(failure -> close(open, subscription));
            for (AgentItemModel item : runtime.listItems(userId, threadId, cursor.get())) {
                send(emitter, new AgentThreadEventDto(item.itemId(), threadId, item.turnId(),
                        "item." + item.type().name().toLowerCase(), item.payload(), item.sequence(), item.createdAt()), open);
                cursor.set(item.sequence());
            }
            emitter.send(SseEmitter.event().name("ready")
                    .data("{\"threadId\":\"" + threadId + "\",\"afterSequence\":" + cursor.get() + "}"));
        } catch (IOException failure) {
            emitter.completeWithError(failure);
        }
        return emitter;
    }

    private void send(SseEmitter emitter, AgentThreadEventDto event, AtomicBoolean open) {
        if (!open.get()) return;
        try {
            emitter.send(SseEmitter.event().id(event.eventId()).name(event.type()).data(event.payload()));
        } catch (IOException failure) {
            close(open, null);
        }
    }

    private void close(AtomicBoolean open, AutoCloseable subscription) {
        if (!open.compareAndSet(true, false) || subscription == null) return;
        try {
            subscription.close();
        } catch (Exception failure) {
            LOGGER.debug("SSE subscription close failed, errorType={}", failure.getClass().getSimpleName());
        }
    }
}
