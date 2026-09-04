package cn.ethan.core.agent.execution;

/**
 * 类型职责：约束资源预算和重复工具失败的稳定停止原因，供 Runtime 与前端投影共同使用。
 *
 * @author ethan
 * @date 2026-09-04
 */
public enum AgentExecutionStopReasonEnum {
    CONTEXT_BUDGET_EXCEEDED,
    OUTPUT_BUDGET_EXCEEDED,
    TOOL_REPEATED_FAILURE
}
