package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达确定性 Workflow 节点进度，限制公开步骤只包含稳定业务字段。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentWorkflowStepPayloadModel(
        String runId,
        String node,
        String status,
        String branch,
        String code,
        long elapsedMillis
) implements AgentItemPayloadValue {

    public AgentWorkflowStepPayloadModel {
        runId = required(runId, "runId");
        node = required(node, "node");
        status = required(status, "status");
        branch = normalize(branch);
        code = normalize(code);
        if (elapsedMillis < 0) {
            throw new IllegalArgumentException("elapsedMillis must not be negative");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.WORKFLOW_STEP;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.strip();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
