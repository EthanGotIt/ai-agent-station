package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 上下文预算策略提供服务
 */
@Service
public class AgentContextPolicyService {

    @Value("${ai-agent.context.max-chars:12000}")
    private int maxChars;

    @Value("${ai-agent.context.compress-threshold:0.80}")
    private double compressThreshold;

    @Value("${ai-agent.context.stop-threshold:0.95}")
    private double stopThreshold;

    @Value("${ai-agent.context.summary-max-chars:1500}")
    private int summaryMaxChars;

    public ContextBudgetPolicyVO buildPolicy() {
        return ContextBudgetPolicyVO.builder()
                .maxChars(maxChars)
                .compressThreshold(compressThreshold)
                .stopThreshold(stopThreshold)
                .summaryMaxChars(summaryMaxChars)
                .build();
    }

}
