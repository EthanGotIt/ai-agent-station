package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达外部动作命令、重试和核验的可审计状态快照。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentExternalActionStatusPayloadModel(
        String commandId,
        String runId,
        String status,
        int attemptCount,
        int retryCycleAttemptCount,
        int maxAttempts,
        String actionType,
        String orderId,
        String nextAttemptAt,
        String code,
        String message,
        String verificationStatus,
        String verificationMessage,
        String verifiedAt
) implements AgentItemPayloadValue {

    public AgentExternalActionStatusPayloadModel {
        commandId = required(commandId, "commandId");
        runId = required(runId, "runId");
        status = required(status, "status");
        actionType = required(actionType, "actionType");
        orderId = optional(orderId);
        nextAttemptAt = optional(nextAttemptAt);
        code = optional(code);
        message = optional(message);
        verificationStatus = optional(verificationStatus);
        verificationMessage = optional(verificationMessage);
        verifiedAt = optional(verifiedAt);
        if (attemptCount < 0 || retryCycleAttemptCount < 0 || maxAttempts < 0) {
            throw new IllegalArgumentException("外部动作尝试次数不能为负数");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.EXTERNAL_ACTION_STATUS;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
