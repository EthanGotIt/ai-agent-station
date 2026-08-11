package cn.ethan.controller;

import cn.ethan.config.AgentRuntimeProperties;
import cn.ethan.core.agent.enums.OutputEventTypeEnum;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.OutputEventModel;
import cn.ethan.core.agent.model.QueuedExecutionModel;
import cn.ethan.core.agent.model.ToolInterventionRequestModel;
import cn.ethan.core.agent.service.AgentRuntimeService;
import cn.ethan.core.workflow.model.WorkflowAnswerRequestModel;
import cn.ethan.dto.AgentCancelResponseDto;
import cn.ethan.dto.AgentChatEventDto;
import cn.ethan.dto.AgentChatRequestDto;
import cn.ethan.dto.AgentChatResponseDto;
import cn.ethan.dto.AgentWorkflowAnswerRequestDto;
import cn.ethan.core.agent.model.AgentMemoryOptionsModel;
import cn.ethan.dto.AgentWorkflowAnswerResponseDto;
import cn.ethan.dto.AgentToolInterventionRequestDto;
import cn.ethan.dto.AgentToolInterventionResponseDto;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 接口控制器：提供同步对话、SSE 对话和请求取消入口。
 *
 * @author ethan
 * @date 2026-08-05
 */
@RestController
@RequestMapping("/api/v1/agent")
public final class AgentController {

    private final AgentRuntimeService runtimeService;
    private final AgentRuntimeProperties runtimeProperties;

    public AgentController(
            AgentRuntimeService runtimeService,
            AgentRuntimeProperties runtimeProperties
    ) {
        this.runtimeService = runtimeService;
        this.runtimeProperties = runtimeProperties;
    }

    @PostMapping("/chat")
    public AgentChatResponseDto chat(
            @Valid @RequestBody AgentChatRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return AgentChatResponseDto.from(runtimeService.handle(
                toModel(body),
                requireUserId(userId)
        ));
    }

    @PostMapping(
            value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8"
    )
    public SseEmitter stream(
            @Valid @RequestBody AgentChatRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            HttpServletResponse response
    ) {
        String currentUserId = requireUserId(userId);
        AgentRequestModel request = toModel(body);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SseEmitter emitter = new SseEmitter(runtimeProperties.streamTimeoutMillis());
        AtomicBoolean streamOpen = new AtomicBoolean(true);
        Runnable cancelRequest = () -> {
            if (streamOpen.compareAndSet(true, false)) {
                runtimeService.cancel(body.requestId(), currentUserId);
            }
        };
        emitter.onCompletion(cancelRequest);
        emitter.onTimeout(cancelRequest);
        emitter.onError(failure -> cancelRequest.run());
        QueuedExecutionModel execution = runtimeService.submit(
                request,
                currentUserId,
                event -> sendEvent(
                        emitter,
                        request.requestId(),
                        currentUserId,
                        event,
                        streamOpen
                )
        );
        execution.completion().whenComplete((result, failure) -> {
            if (streamOpen.compareAndSet(true, false)) {
                // Runtime 已在完成 Future 前发送稳定的 error/done 事件
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/workflow-runs/{runId}/answers")
    public AgentWorkflowAnswerResponseDto answer(
            @PathVariable String runId,
            @Valid @RequestBody AgentWorkflowAnswerRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return AgentWorkflowAnswerResponseDto.from(runtimeService.answer(
                toAnswerModel(runId, body),
                requireUserId(userId)
        ));
    }

    @PostMapping(
            value = "/workflow-runs/{runId}/answers/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8"
    )
    public SseEmitter answerStream(
            @PathVariable String runId,
            @Valid @RequestBody AgentWorkflowAnswerRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            HttpServletResponse response
    ) {
        String currentUserId = requireUserId(userId);
        WorkflowAnswerRequestModel answerRequest = toAnswerModel(runId, body);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        SseEmitter emitter = new SseEmitter(runtimeProperties.streamTimeoutMillis());
        AtomicBoolean streamOpen = new AtomicBoolean(true);
        Runnable cancelRequest = () -> {
            if (streamOpen.compareAndSet(true, false)) {
                runtimeService.cancel(body.requestId(), currentUserId);
            }
        };
        emitter.onCompletion(cancelRequest);
        emitter.onTimeout(cancelRequest);
        emitter.onError(failure -> cancelRequest.run());
        QueuedExecutionModel execution = runtimeService.submitAnswer(
                answerRequest,
                currentUserId,
                event -> sendEvent(
                        emitter,
                        body.requestId(),
                        currentUserId,
                        event,
                        streamOpen
                )
        );
        execution.completion().whenComplete((result, failure) -> {
            if (streamOpen.compareAndSet(true, false)) {
                emitter.complete();
            }
        });
        return emitter;
    }

    @PostMapping("/requests/{requestId}/interventions/{replyId}")
    public ResponseEntity<AgentToolInterventionResponseDto> decideToolIntervention(
            @PathVariable String requestId,
            @PathVariable String replyId,
            @Valid @RequestBody AgentToolInterventionRequestDto body,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        String currentRequestId = requireIdentifier(requestId, "requestId");
        String currentReplyId = requireIdentifier(replyId, "replyId");
        boolean accepted = runtimeService.decideToolIntervention(new ToolInterventionRequestModel(
                currentRequestId, body.sessionId(), currentReplyId, body.toolCallIds(), body.decision()
        ), requireUserId(userId));
        return accepted
                ? ResponseEntity.ok(new AgentToolInterventionResponseDto(currentRequestId, currentReplyId, true))
                : ResponseEntity.status(409).body(new AgentToolInterventionResponseDto(
                        currentRequestId, currentReplyId, false
                ));
    }

    @DeleteMapping("/requests/{requestId}")
    public ResponseEntity<AgentCancelResponseDto> cancel(
            @PathVariable String requestId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        String currentUserId = requireUserId(userId);
        String currentRequestId = requireIdentifier(requestId, "requestId");
        return ResponseEntity.ok(new AgentCancelResponseDto(
                currentRequestId,
                runtimeService.cancel(currentRequestId, currentUserId)
        ));
    }

    private void sendEvent(SseEmitter emitter, String requestId, String userId,
                           OutputEventModel event, AtomicBoolean streamOpen) {
        if (!streamOpen.get()) {
            return;
        }
        AgentChatEventDto eventDto = AgentChatEventDto.from(event);
        try {
            emitter.send(SseEmitter.event()
                    .name(eventDto.type())
                    .data(eventDto.data()));
            if (event.type() == OutputEventTypeEnum.DONE
                    && streamOpen.compareAndSet(true, false)) {
                // 已发送终态事件后立即收敛 HTTP 流，避免取消场景等待异步 Future 回调而悬挂。
                emitter.complete();
            }
        } catch (IOException failure) {
            if (streamOpen.compareAndSet(true, false)) {
                runtimeService.cancel(requestId, userId);
                emitter.completeWithError(failure);
            }
        }
    }

    private String requireUserId(String userId) {
        return requireIdentifier(userId, "X-User-Id");
    }

    private String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(name + " 长度不能超过 128");
        }
        return normalized;
    }

    private AgentRequestModel toModel(AgentChatRequestDto body) {
        return new AgentRequestModel(
                body.requestId(),
                body.sessionId(),
                body.message(),
                body.memory() == null ? AgentMemoryOptionsModel.DEFAULT : body.memory().toModel()
        );
    }

    private WorkflowAnswerRequestModel toAnswerModel(
            String runId,
            AgentWorkflowAnswerRequestDto body
    ) {
        return new WorkflowAnswerRequestModel(
                body.requestId(),
                body.sessionId(),
                requireIdentifier(runId, "runId"),
                body.questionId(),
                body.checkpointId(),
                body.expectedVersion(),
                body.answers(),
                body.memory() == null ? AgentMemoryOptionsModel.DEFAULT : body.memory().toModel()
        );
    }
}
