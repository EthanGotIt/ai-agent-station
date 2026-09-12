package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;

/**
 * 类型职责：表达 Workflow Checkpoint 的批准或拒绝事实，保留版本与事实指纹用于幂等核验。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentWorkflowDecisionPayloadModel(
        String runId,
        String checkpointId,
        long expectedVersion,
        AgentWorkflowDecisionEnum decision,
        String factsFingerprint
) implements AgentItemPayloadValue {

    public AgentWorkflowDecisionPayloadModel {
        runId = required(runId, "runId");
        checkpointId = required(checkpointId, "checkpointId");
        if (expectedVersion < 0 || decision == null) {
            throw new IllegalArgumentException("decision version and decision must be valid");
        }
        factsFingerprint = factsFingerprint == null ? "" : factsFingerprint.strip();
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.WORKFLOW_DECISION;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.strip();
    }
}
