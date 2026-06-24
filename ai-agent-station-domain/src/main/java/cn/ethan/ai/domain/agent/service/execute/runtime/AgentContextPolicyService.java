package cn.ethan.ai.domain.agent.service.execute.runtime;

import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 上下文预算策略提供服务
 */
@Service
public class AgentContextPolicyService {

    @Value("${ai-agent.context.max-context-units:12000}")
    private int maxContextUnits;

    @Value("${ai-agent.context.stop-threshold:0.95}")
    private double stopThreshold;

    public ContextBudgetPolicyVO buildPolicy() {
        return ContextBudgetPolicyVO.builder()
                .maxContextUnits(maxContextUnits)
                .stopThreshold(stopThreshold)
                .build();
    }

}
