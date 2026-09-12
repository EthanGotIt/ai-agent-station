package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达 Workflow 人工确认事实，限制公开字段不携带内部身份或持久化细节。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentWorkflowCheckpointPayloadModel(
        String checkpointId,
        String runId,
        String nodeId,
        String actionType,
        String orderId,
        String impactSummary,
        String factsFingerprint,
        long version
) implements AgentItemPayloadValue {

    public AgentWorkflowCheckpointPayloadModel {
        checkpointId = required(checkpointId, "checkpointId");
        runId = required(runId, "runId");
        nodeId = required(nodeId, "nodeId");
        actionType = required(actionType, "actionType");
        orderId = required(orderId, "orderId");
        impactSummary = normalize(impactSummary);
        factsFingerprint = required(factsFingerprint, "factsFingerprint");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.WORKFLOW_CHECKPOINT;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
