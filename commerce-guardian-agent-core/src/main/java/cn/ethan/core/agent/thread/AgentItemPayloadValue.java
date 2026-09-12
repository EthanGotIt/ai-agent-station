package cn.ethan.core.agent.thread;

/**
 * 类型职责：定义可以直接交给 Item Codec 的受控 payload 值集合。
 *
 * <p>该接口只描述业务字段，不持有 JSON 或持久化依赖；历史字符串仍由兼容边界负责读取。</p>
 *
 * @author ethan
 * @date 2026-09-13
 */
public sealed interface AgentItemPayloadValue
        permits AgentTurnStatePayloadModel, AgentToolCallPayloadModel,
        AgentToolResultPayloadModel, AgentWorkflowResultPayloadModel,
        AgentDecisionPayloadModel, AgentWorkflowStepPayloadModel,
        AgentOrderActionPayloadModel, AgentQuestionCardPayloadModel,
        AgentWorkflowCheckpointPayloadModel, AgentQuestionAnswerPayloadModel,
        AgentWorkflowDecisionPayloadModel, AgentContinuationPayloadModel,
        AgentExecutionEventPayloadModel, AgentErrorPayloadModel,
        AgentExternalActionStatusPayloadModel, AgentOrderListPayloadModel,
        AgentOrderDetailPayloadModel, AgentLogisticsTimelinePayloadModel {

    /** 返回该值对应的 Item 类型，防止值与 envelope kind 错配。 */
    AgentItemTypeEnum type();
}
