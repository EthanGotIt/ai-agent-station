package cn.ethan.ai.domain.agent.model.valobj;

import lombok.Getter;

/**
 * 运行上下文窗口保护值对象，默认使用轻量估算器，后续可替换为模型 tokenizer 实现。
 */
@Getter
public class ContextWindowGuardVO {

    public static final int DEFAULT_MAX_CONTEXT_UNITS = 12000;

    private final int maxContextUnits;

    private final double compactHistoryThreshold;

    private final double stopLlmCallThreshold;

    private final int summaryMaxChars;

    private final ContextUnitEstimator contextUnitEstimator;

    private int usedContextUnits;

    private boolean historyCompacted;

    private int latestOriginalChars;

    private int latestCompressedChars;

    private String historySummary;

    public ContextWindowGuardVO() {
        this(ContextBudgetPolicyVO.builder().build());
    }

    public ContextWindowGuardVO(ContextBudgetPolicyVO policy) {
        this(policy, HeuristicContextUnitEstimator.INSTANCE);
    }

    public ContextWindowGuardVO(ContextBudgetPolicyVO policy, ContextUnitEstimator contextUnitEstimator) {
        ContextBudgetPolicyVO actualPolicy = policy == null ? ContextBudgetPolicyVO.builder().build() : policy;
        this.maxContextUnits = actualPolicy.getMaxChars() <= 0 ? DEFAULT_MAX_CONTEXT_UNITS : actualPolicy.getMaxChars();
        this.compactHistoryThreshold = actualPolicy.getCompressThreshold() <= 0 ? 0.80D : actualPolicy.getCompressThreshold();
        this.stopLlmCallThreshold = actualPolicy.getStopThreshold() <= 0 ? 0.95D : actualPolicy.getStopThreshold();
        this.summaryMaxChars = actualPolicy.getSummaryMaxChars() <= 0 ? 1500 : actualPolicy.getSummaryMaxChars();
        this.contextUnitEstimator = contextUnitEstimator == null ? HeuristicContextUnitEstimator.INSTANCE : contextUnitEstimator;
    }

    public void record(String text) {
        int contextUnits = estimate(text);
        this.usedContextUnits += contextUnits;
    }

    public int estimate(String text) {
        return contextUnitEstimator.estimate(text);
    }

    public boolean shouldCompactHistory() {
        return !historyCompacted && usageRatio() >= compactHistoryThreshold;
    }

    public boolean shouldCompactHistory(int originalChars) {
        this.latestOriginalChars = originalChars;
        return !historyCompacted && originalChars >= (int) Math.ceil(maxContextUnits * compactHistoryThreshold);
    }

    public boolean shouldStopNewLlmCall() {
        return usageRatio() >= stopLlmCallThreshold;
    }

    public void markHistoryCompacted() {
        this.historyCompacted = true;
    }

    public void updateHistorySnapshot(int originalChars, int compressedChars, String summary) {
        this.latestOriginalChars = Math.max(originalChars, 0);
        this.latestCompressedChars = Math.max(compressedChars, 0);
        this.historySummary = summary;
        if (compressedChars > 0 && compressedChars < originalChars) {
            this.historyCompacted = true;
        }
    }

    public double usageRatio() {
        return maxContextUnits == 0 ? 1.0 : (double) usedContextUnits / maxContextUnits;
    }

}
