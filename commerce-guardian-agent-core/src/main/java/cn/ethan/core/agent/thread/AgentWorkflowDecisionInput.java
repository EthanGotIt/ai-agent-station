package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;

/**
 * 类型职责：保存 Workflow Checkpoint 决策 Turn 的可恢复结构化输入。
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentWorkflowDecisionInput(
        String runId,
        String checkpointId,
        long expectedVersion,
        AgentWorkflowDecisionEnum decision,
        String factsFingerprint
) {
    public AgentWorkflowDecisionInput {
        if (runId == null || runId.isBlank() || checkpointId == null || checkpointId.isBlank()
                || expectedVersion < 0 || decision == null) {
            throw new IllegalArgumentException("Workflow Checkpoint 决策输入不完整");
        }
        runId = runId.trim();
        checkpointId = checkpointId.trim();
        factsFingerprint = factsFingerprint == null ? "" : factsFingerprint.trim();
    }
}
