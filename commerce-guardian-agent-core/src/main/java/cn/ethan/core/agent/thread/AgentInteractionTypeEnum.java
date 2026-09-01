package cn.ethan.core.agent.thread;

/**
 * 类型职责：区分 Thread 当前唯一开放的人机交互类型。
 *
 * @author ethan
 * @date 2026-08-27
 */
public enum AgentInteractionTypeEnum {
    QUESTION_CARD,
    WORKFLOW_CHECKPOINT
}
