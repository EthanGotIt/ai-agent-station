package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达 Workflow 结果 Item 的结构化状态和用户可见说明。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentWorkflowResultPayloadModel(
        String runId,
        String status,
        String message
) implements AgentItemPayloadValue {

    public AgentWorkflowResultPayloadModel {
        runId = required(runId, "runId");
        status = required(status, "status");
        message = message == null || message.isBlank() ? null : message.trim();
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.WORKFLOW_RESULT;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
