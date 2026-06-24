package cn.ethan.ai.domain.agent.model.valobj;

import lombok.Getter;

/**
 * 单次模型调用的近似上下文预算，不跨调用累计。
 */
@Getter
public class ContextWindowGuardVO {

    public static final int DEFAULT_MAX_CONTEXT_UNITS = 12000;

    private final int maxContextUnits;

    private final double stopLlmCallThreshold;

    private final ContextUnitEstimator contextUnitEstimator;

    private int latestInputUnits;

    public ContextWindowGuardVO() {
        this(ContextBudgetPolicyVO.builder().build());
    }

    public ContextWindowGuardVO(ContextBudgetPolicyVO policy) {
        this(policy, HeuristicContextUnitEstimator.INSTANCE);
    }

    public ContextWindowGuardVO(ContextBudgetPolicyVO policy, ContextUnitEstimator estimator) {
        ContextBudgetPolicyVO actual = policy == null ? ContextBudgetPolicyVO.builder().build() : policy;
        this.maxContextUnits = actual.getMaxContextUnits() <= 0
                ? DEFAULT_MAX_CONTEXT_UNITS : actual.getMaxContextUnits();
        this.stopLlmCallThreshold = actual.getStopThreshold() <= 0 ? 0.95D : actual.getStopThreshold();
        this.contextUnitEstimator = estimator == null ? HeuristicContextUnitEstimator.INSTANCE : estimator;
    }

    public int inspectPrompt(String prompt) {
        latestInputUnits = estimate(prompt);
        return latestInputUnits;
    }

    public boolean shouldStopNewLlmCall(String prompt) {
        return inspectPrompt(prompt) >= (int) Math.floor(maxContextUnits * stopLlmCallThreshold);
    }

    public int estimate(String text) {
        return contextUnitEstimator.estimate(text);
    }

    public double latestUsageRatio() {
        return maxContextUnits == 0 ? 1D : (double) latestInputUnits / maxContextUnits;
    }
}
