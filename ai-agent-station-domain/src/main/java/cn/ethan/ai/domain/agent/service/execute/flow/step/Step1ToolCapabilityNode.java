package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.service.execute.flow.FlowToolCapabilityService;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 步骤1：工具能力摘要节点
 */
@Slf4j
@Service
public class Step1ToolCapabilityNode extends AbstractExecuteSupport {

    @Resource
    private FlowToolCapabilityService flowToolCapabilityService;

    @Resource
    private Step2PlanGenerateNode step2PlanGenerateNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("步骤1：生成工具能力摘要");

        Set<String> allowedTools = flowToolCapabilityService.loadAllowedTools(executionContext.getAiAgentClientFlowConfigVOMap());
        String toolCapabilitySummary = flowToolCapabilityService.buildToolCapabilitySummary(allowedTools);
        executionContext.setAllowedTools(allowedTools);
        executionContext.setToolCapabilitySummary(toolCapabilitySummary);

        AgentRunAggregate run = currentRun(executionContext);
        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                nextStreamStep(executionContext),
                "analysis_tools",
                toolCapabilitySummary,
                requestParameter.getSessionId(),
                run.runId()
        ));

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        return step2PlanGenerateNode;
    }
}
