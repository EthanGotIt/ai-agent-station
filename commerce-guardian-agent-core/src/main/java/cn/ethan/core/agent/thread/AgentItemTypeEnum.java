package cn.ethan.core.agent.thread;

/**
 * Thread 内可恢复的事实类型；原始 Thinking 不属于公开 Item。
 *
 * @author ethan
 * @date 2026-08-19
 */
public enum AgentItemTypeEnum {
    USER_MESSAGE,
    TURN_STATE,
    ASSISTANT_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    WORKFLOW_STARTED,
    QUESTION_CARD,
    QUESTION_ANSWER,
    WORKFLOW_CHECKPOINT,
    WORKFLOW_DECISION,
    /** 旧版本 Workflow Question 事实，仅用于只读历史兼容。 */
    WORKFLOW_QUESTION,
    /** 旧版本回答事实，仅用于只读历史兼容。 */
    WORKFLOW_ANSWER,
    WORKFLOW_RESULT,
    EXTERNAL_ACTION_STATUS,
    ORDER_LIST,
    ORDER_DETAIL,
    LOGISTICS_TIMELINE,
    ORDER_ACTION_REQUEST,
    WORKFLOW_STEP,
    AGENT_CONTINUATION,
    AGENT_DECISION,
    EXECUTION_EVENT,
    ERROR
}
