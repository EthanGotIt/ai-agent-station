package cn.ethan.ai.domain.agent.model;

/**
 * 一次状态机执行结果，携带本次 Turn 可提交的恢复边界。
 */
public record AfterSalesStateMachineResult(AfterSalesAgentState state,
                                           Checkpoint checkpoint,
                                           String nextNode) {
}
