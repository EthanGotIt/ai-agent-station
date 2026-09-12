package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.coordination.AgentDecisionTypeEnum;

/**
 * 类型职责：表达 Agent 的受控决策事实，供停止、回退和纠正路径共享编码边界。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentDecisionPayloadModel(
        AgentDecisionTypeEnum decision,
        int cycleNo,
        String runId,
        String code,
        boolean correctionAttempt
) implements AgentItemPayloadValue {

    public AgentDecisionPayloadModel {
        if (decision == null || cycleNo < 0) {
            throw new IllegalArgumentException("decision and cycleNo must be valid");
        }
        runId = normalize(runId);
        code = normalize(code);
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.AGENT_DECISION;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
