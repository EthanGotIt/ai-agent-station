package cn.ethan.app.agent.api;

import cn.ethan.core.agent.action.ExternalActionService;
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
 * 类型职责：负责 Workflow QuestionCard 回答和外部动作人工重试的 HTTP 协议转换。
 *
 * @author ethan
 * @date 2026-08-20
 */
@RestController
@RequestMapping("/api/agent")
public final class AgentWorkflowController {

    private final AgentTurnRuntimeService runtime;
    private final AgentUserContext userContext;
    private final ExternalActionService actions;

    public AgentWorkflowController(
            AgentTurnRuntimeService runtime,
            AgentUserContext userContext,
            ExternalActionService actions
    ) {
        this.runtime = runtime;
        this.userContext = userContext;
        this.actions = actions;
    }

    @PostMapping("/workflow-runs/{runId}/questions/{questionId}/answers")
    public ResponseEntity<AgentTurnAcceptedResponseDto> answer(
            @PathVariable String runId,
            @PathVariable String questionId,
            @Valid @RequestBody AgentWorkflowQuestionAnswerRequestDto body,
            HttpServletRequest request
    ) {
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(runtime.answerQuestion(
                userContext.currentUserId(request), body.clientRequestId(), runId, questionId,
                body.checkpointId(), body.expectedVersion(), body.answers()
        )));
    }

    @PostMapping("/workflow-runs/{runId}/retry")
    public AgentWorkflowRetryResponseDto retry(
            @PathVariable String runId,
            HttpServletRequest request
    ) {
        String userId = userContext.currentUserId(request);
        var retried = actions.retry(userId, runId);
        return new AgentWorkflowRetryResponseDto(runId, retried.commandId(), retried.status().name(),
                retried.idempotencyKey());
    }
}
