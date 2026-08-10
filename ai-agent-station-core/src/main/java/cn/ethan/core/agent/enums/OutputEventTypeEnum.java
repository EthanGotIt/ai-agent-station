package cn.ethan.core.agent.enums;

/**
 * 输出事件类型枚举：定义统一输出边界允许产生的事件类型。
 *
 * @author ethan
 * @date 2026-08-05
 */
public enum OutputEventTypeEnum {
    ROUTE,
    NODE,
    TOOL,
    PROGRESS,
    CONTENT,
    RESULT,
    WORKFLOW_QUESTION,
    INTERVENTION,
    ERROR,
    DONE
}
