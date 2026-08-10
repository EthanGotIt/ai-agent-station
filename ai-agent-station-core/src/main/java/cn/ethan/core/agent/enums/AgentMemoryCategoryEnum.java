package cn.ethan.core.agent.enums;

/**
 * 记忆类别：区分用户偏好和仅在当前会话内有效的任务上下文。
 *
 * @author ethan
 * @date 2026-08-10
 */
public enum AgentMemoryCategoryEnum {
    PREFERENCE,
    TASK_CONTEXT,
    LEGACY
}
