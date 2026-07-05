package cn.ethan.ai.domain.agent.model;

/**
 * 售后Agent状态机快照。
 */
public record AfterSalesAgentStateSnapshot(String checkpointId,
                                           String nextNode,
                                           AfterSalesAgentState state) {
}
