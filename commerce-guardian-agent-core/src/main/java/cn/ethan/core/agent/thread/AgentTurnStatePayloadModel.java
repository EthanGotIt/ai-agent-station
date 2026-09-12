package cn.ethan.core.agent.thread;

/**
 * 类型职责：表达 Turn 生命周期状态 Item 的结构化字段。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentTurnStatePayloadModel(
        AgentTurnStatusEnum status,
        String errorCode
) implements AgentItemPayloadValue {

    public AgentTurnStatePayloadModel {
        if (status == null) {
            throw new IllegalArgumentException("Turn 状态不能为空");
        }
        errorCode = errorCode == null || errorCode.isBlank() ? null : errorCode.trim();
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.TURN_STATE;
    }
}
