package cn.ethan.core.agent.coordination;

/**
 * 类型职责：约束协调 Agent 在一轮执行结束时可选择的受控结果。
 *
 * @author ethan
 * @date 2026-08-26
 */
public enum AgentDecisionTypeEnum {
    FINISH,
    START_WORKFLOW,
    ASK_USER,
    /** 旧版本兼容值；新控制 Tool 使用 ASK_USER。 */
    WAIT_USER,
    STOP_LIMIT,
    FALLBACK
}
