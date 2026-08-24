package cn.ethan.app.agent.api;

import cn.ethan.core.agent.execution.AgentTurnRuntimeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 类型职责：负责 Turn 入队和协作取消的 HTTP 协议转换。
 *
 * @author ethan
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentTurnController {

    private final AgentTurnRuntimeService runtime;
    private final AgentUserContext userContext;

    public AgentTurnController(AgentTurnRuntimeService runtime, AgentUserContext userContext) {
        this.runtime = runtime;
        this.userContext = userContext;
    }

    @PostMapping("/threads/{threadId}/turns")
    public ResponseEntity<AgentTurnAcceptedResponseDto> submit(
            @PathVariable String threadId,
            @Valid @RequestBody AgentTurnSubmitRequestDto body,
            HttpServletRequest request
    ) {
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(
                runtime.submitTurn(userContext.currentUserId(request), threadId,
                        body.clientRequestId(), body.message())
        ));
    }

    @PostMapping("/threads/{threadId}/order-actions")
    public ResponseEntity<AgentTurnAcceptedResponseDto> orderAction(
            @PathVariable String threadId,
            @Valid @RequestBody AgentOrderActionRequestDto body,
            HttpServletRequest request
    ) {
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(
                runtime.submitOrderAction(userContext.currentUserId(request), threadId,
                        body.clientRequestId(), body.sourceTurnId(), body.orderId(), body.actionType())
        ));
    }

    @PostMapping("/turns/{turnId}/cancel")
    public ResponseEntity<AgentTurnCancelResponseDto> cancel(
            @PathVariable String turnId,
            HttpServletRequest request
    ) {
        String userId = userContext.currentUserId(request);
        boolean cancelled = runtime.cancel(userId, turnId);
        return cancelled
                ? ResponseEntity.ok(new AgentTurnCancelResponseDto(turnId, true))
                : ResponseEntity.notFound().build();
    }
}
