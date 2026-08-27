package cn.ethan.app.agent.api;

import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 类型职责：接收固定 Workflow Checkpoint 的批准/拒绝和提交时事实指纹。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentWorkflowCheckpointDecisionRequestDto(
        @NotBlank @Size(max = AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH) String clientRequestId,
        @NotNull @Min(0) Long expectedVersion,
        @NotNull AgentWorkflowDecisionEnum decision,
        @NotBlank @Size(max = 128) String factsFingerprint
) {
}
