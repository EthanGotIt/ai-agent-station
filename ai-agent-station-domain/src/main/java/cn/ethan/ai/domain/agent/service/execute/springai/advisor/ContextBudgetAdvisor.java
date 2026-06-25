package cn.ethan.ai.domain.agent.service.execute.springai.advisor;

import cn.ethan.ai.domain.agent.model.valobj.ContextUnitEstimator;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Advisor Chain 的第一层上下文预算守卫。
 */
public class ContextBudgetAdvisor implements BaseAdvisor {

    public static final int DEFAULT_MAX_CONTEXT_UNITS = 12000;

    public static final double DEFAULT_STOP_THRESHOLD = 0.95D;

    private final int maxContextUnits;

    private final double stopThreshold;

    private final ContextUnitEstimator estimator;

    public ContextBudgetAdvisor() {
        this(DEFAULT_MAX_CONTEXT_UNITS, DEFAULT_STOP_THRESHOLD, new HeuristicContextUnitEstimator());
    }

    public ContextBudgetAdvisor(int maxContextUnits,
                                double stopThreshold,
                                ContextUnitEstimator estimator) {
        this.maxContextUnits = Math.max(1, maxContextUnits);
        this.stopThreshold = Math.max(0.1D, Math.min(1D, stopThreshold));
        this.estimator = estimator == null ? new HeuristicContextUnitEstimator() : estimator;
    }

    @Override
    public @NonNull ChatClientRequest before(ChatClientRequest request, @NonNull AdvisorChain advisorChain) {
        int units = estimator.estimate(request.prompt().getContents());
        int stopUnits = (int) Math.ceil(maxContextUnits * stopThreshold);
        if (units >= stopUnits) {
            throw new IllegalStateException("当前单次 Prompt 超过近似上下文预算，已拒绝模型调用。contextUnits="
                    + units + ", stopUnits=" + stopUnits);
        }
        return request.mutate()
                .context(SpringAiAdvisorContextKeys.CONTEXT_UNITS, units)
                .context(SpringAiAdvisorContextKeys.CONTEXT_BUDGET_REJECTED, false)
                .build();
    }

    @Override
    public @NonNull ChatClientResponse after(@NonNull ChatClientResponse response, @NonNull AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    public int maxContextUnits() {
        return maxContextUnits;
    }

    public double stopThreshold() {
        return stopThreshold;
    }
}
