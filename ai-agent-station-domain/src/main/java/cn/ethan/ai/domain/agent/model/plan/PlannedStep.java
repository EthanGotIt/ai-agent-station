package cn.ethan.ai.domain.agent.model.plan;

import cn.ethan.ai.types.common.id.StepId;

import java.util.Map;

/**
 * 退款计划单步。
 *
 * @param stepId        步骤唯一标识，由规划器按步骤内容确定性生成
 * @param action        动作类型：ASK_USER 或 TOOL_CALL
 * @param targetField   目标字段，如 orderId、refundReason
 * @param toolName      TOOL_CALL 时填写的工具名，如 query_order
 * @param input         TOOL_CALL 时的输入参数
 * @param reasonForUser ASK_USER 时展示给用户的询问原因
 */
public record PlannedStep(
        StepId stepId,
        String action,
        String targetField,
        String toolName,
        Map<String, Object> input,
        String reasonForUser,
        String reasonCode,
        String expectedEvidence
) {

    public PlannedStep(StepId stepId,
                       String action,
                       String targetField,
                       String toolName,
                       Map<String, Object> input,
                       String reasonForUser) {
        this(stepId, action, targetField, toolName, input, reasonForUser, null, null);
    }
}
