package cn.ethan.core.agent.workflow;

/**
 * 类型职责：限制 QuestionCard 的生命周期；取消也是已结束的人机交互事实。
 *
 * @author ethan
 * @date 2026-08-27
 */
public enum AgentQuestionCardStatusEnum {
    OPEN,
    ANSWERED,
    CANCELLED
}
