package cn.ethan.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文预算策略
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextBudgetPolicyVO {

    @Builder.Default
    private int maxChars = 12000;

    @Builder.Default
    private double compressThreshold = 0.80D;

    @Builder.Default
    private double stopThreshold = 0.95D;

    @Builder.Default
    private int summaryMaxChars = 1500;

}
