package cn.ethan.app.agent.api;

import cn.ethan.core.agent.action.ExternalActionService;
import cn.ethan.core.agent.execution.AgentTurnRuntimeService;
import cn.ethan.core.agent.execution.AgentQuestionAnswerAdmission;
import cn.ethan.core.agent.execution.AgentQuestionAnswerAdmissionCommand;
import cn.ethan.core.agent.execution.AgentWorkflowDecisionAdmission;
import cn.ethan.core.agent.execution.AgentWorkflowDecisionAdmissionCommand;
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
    private final AgentQuestionAnswerAdmission questionAdmission;
    private final AgentWorkflowDecisionAdmission decisionAdmission;

    public AgentWorkflowController(
            AgentTurnRuntimeService runtime,
            AgentUserContext userContext,
            ExternalActionService actions,
            AgentQuestionAnswerAdmission questionAdmission,
            AgentWorkflowDecisionAdmission decisionAdmission
    ) {
        this.runtime = runtime;
        this.userContext = userContext;
        this.actions = actions;
        this.questionAdmission = questionAdmission;
        this.decisionAdmission = decisionAdmission;
    }

    @PostMapping("/questions/{questionId}/answers")
    public ResponseEntity<AgentTurnAcceptedResponseDto> answerQuestionCard(
            @PathVariable String questionId,
            @Valid @RequestBody AgentQuestionAnswerRequestDto body,
            HttpServletRequest request
    ) {
        if (questionAdmission == null) {
            throw new IllegalStateException("QuestionCard admission 未装配");
        }
        String userId = userContext.currentUserId(request);
        var result = questionAdmission.admit(new AgentQuestionAnswerAdmissionCommand(
                userId, questionId, body.clientRequestId(), body.expectedVersion(), body.answers(), body.action()));
        runtime.enqueuePersisted(result.turn());
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(result.turn()));
    }

    @PostMapping("/workflow-runs/{runId}/checkpoints/{checkpointId}/decisions")
    public ResponseEntity<AgentTurnAcceptedResponseDto> decideCheckpoint(
            @PathVariable String runId,
            @PathVariable String checkpointId,
            @Valid @RequestBody AgentWorkflowCheckpointDecisionRequestDto body,
            HttpServletRequest request
    ) {
        if (decisionAdmission == null) {
            throw new IllegalStateException("Workflow Checkpoint admission 未装配");
        }
        String userId = userContext.currentUserId(request);
        var result = decisionAdmission.admit(new AgentWorkflowDecisionAdmissionCommand(
                userId, runId, checkpointId, body.clientRequestId(), body.expectedVersion(),
                body.decision(), body.factsFingerprint()));
        runtime.enqueuePersisted(result.turn());
        return ResponseEntity.accepted().body(AgentTurnAcceptedResponseDto.from(result.turn()));
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
