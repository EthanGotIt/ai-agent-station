package cn.ethan.core.agent.thread;

/**
 * Thread 内可恢复的事实类型；原始 Thinking 不属于公开 Item。
 *
 * @author ethan
 * @date 2026-08-19
 */
public enum AgentItemTypeEnum {
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    WORKFLOW_STARTED,
    WORKFLOW_QUESTION,
    WORKFLOW_ANSWER,
    WORKFLOW_RESULT,
    EXTERNAL_ACTION_STATUS,
    EXECUTION_EVENT,
    ERROR
}
