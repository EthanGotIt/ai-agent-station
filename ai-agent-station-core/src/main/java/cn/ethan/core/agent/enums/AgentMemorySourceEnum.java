package cn.ethan.core.agent.enums;

/**
 * 记忆来源枚举：用于区分 ReAct 和确定性 Workflow 的记录策略。
 *
 * @author ethan
 * @date 2026-08-09
 */
public enum AgentMemorySourceEnum {
    REACT,
    WORKFLOW,
    MANUAL
}
