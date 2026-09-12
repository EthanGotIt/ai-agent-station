package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达由外部动作事实触发的 Agent 续跑请求，供恢复和幂等校验使用。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentContinuationPayloadModel(
        String rootTurnId,
        String parentTurnId,
        String triggerRunId,
        String triggerCommandId,
        String triggerStatus,
        long triggerSequence,
        int cycleNo
) implements AgentItemPayloadValue {

    public AgentContinuationPayloadModel {
        rootTurnId = required(rootTurnId, "rootTurnId");
        parentTurnId = required(parentTurnId, "parentTurnId");
        triggerRunId = required(triggerRunId, "triggerRunId");
        triggerCommandId = optional(triggerCommandId);
        triggerStatus = required(triggerStatus, "triggerStatus");
        if (triggerSequence < 0) {
            throw new IllegalArgumentException("triggerSequence 不能为负数");
        }
        if (cycleNo < 1) {
            throw new IllegalArgumentException("cycleNo 必须从 1 开始");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.AGENT_CONTINUATION;
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
