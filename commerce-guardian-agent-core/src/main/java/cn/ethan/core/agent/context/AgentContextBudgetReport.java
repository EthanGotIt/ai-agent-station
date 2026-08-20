package cn.ethan.core.agent.context;

/**
 * 类型职责：报告一次上下文组装的预算、快照续接和降级结果，供运行时观测使用。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentContextBudgetReport(
        int estimatedTokens,
        int inputBudget,
        long snapshotThroughSequence,
        boolean compressed,
        boolean degraded
) {
}
