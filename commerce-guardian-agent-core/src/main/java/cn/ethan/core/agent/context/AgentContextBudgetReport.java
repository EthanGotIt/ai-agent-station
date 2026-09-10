package cn.ethan.core.agent.context;

/**
 * 类型职责：报告一次上下文组装的预算、固定水位读取和降级结果，供运行时观测使用。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentContextBudgetReport(
        int estimatedTokens,
        int inputBudget,
        long snapshotThroughSequence,
        boolean compressed,
        boolean degraded,
        int droppedItems,
        long readWatermark,
        long coveredThroughSequence,
        int readItemCount,
        boolean historyComplete,
        int peakEstimatedTokens,
        int pressurePrunedToolResults
) {

    /** 保留旧的五/六字段调用边界；2A-1 不再丢弃 Item，新增字段记录固定水位和实际覆盖范围。 */
    public AgentContextBudgetReport(
            int estimatedTokens,
            int inputBudget,
            long snapshotThroughSequence,
            boolean compressed,
            boolean degraded
    ) {
        this(estimatedTokens, inputBudget, snapshotThroughSequence, compressed, degraded,
                0, 0L, snapshotThroughSequence, 0, true, estimatedTokens, 0);
    }

    public AgentContextBudgetReport(
            int estimatedTokens,
            int inputBudget,
            long snapshotThroughSequence,
            boolean compressed,
            boolean degraded,
            int droppedItems
    ) {
        this(estimatedTokens, inputBudget, snapshotThroughSequence, compressed, degraded,
                droppedItems, 0L, snapshotThroughSequence, 0, true, estimatedTokens, 0);
    }

    /** 将同一 Turn 内已经观测到的历史峰值写入当前报告。 */
    public AgentContextBudgetReport withPeakEstimatedTokens(int peak) {
        return new AgentContextBudgetReport(estimatedTokens, inputBudget, snapshotThroughSequence,
                compressed, degraded, droppedItems, readWatermark, coveredThroughSequence,
                readItemCount, historyComplete, Math.max(estimatedTokens, peak), pressurePrunedToolResults);
    }
}
