package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;

/**
 * 类型职责：描述一次固定 Workflow Checkpoint 决策 admission 请求。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentWorkflowDecisionAdmissionCommand(
        String userId,
        String runId,
        String checkpointId,
        String clientRequestId,
        long expectedVersion,
        AgentWorkflowDecisionEnum decision,
        String factsFingerprint
) {

    public AgentWorkflowDecisionAdmissionCommand {
        userId = identity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        runId = identity(runId, "runId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        checkpointId = identity(checkpointId, "checkpointId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        clientRequestId = identity(clientRequestId, "clientRequestId", AgentTurnModel.MAX_CLIENT_REQUEST_ID_LENGTH);
        if (expectedVersion < 0 || decision == null) {
            throw new IllegalArgumentException("Workflow Checkpoint 决策参数无效");
        }
        factsFingerprint = factsFingerprint == null ? "" : factsFingerprint.trim();
    }

    private static String identity(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }
}
