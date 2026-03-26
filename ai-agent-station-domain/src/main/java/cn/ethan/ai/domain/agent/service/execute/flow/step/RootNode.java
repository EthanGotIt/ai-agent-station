package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 流程执行根节点
 */
@Slf4j
@Service("flowRootNode")
public class RootNode extends AbstractExecuteSupport {

    @Resource
    private Step1McpToolsAnalysisNode step1McpToolsAnalysisNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {

        Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap = repository.queryAiAgentClientFlowConfig(requestParameter.getAiAgentId());

        dynamicContext.setAiAgentClientFlowConfigVOMap(aiAgentClientFlowConfigVOMap);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(requestParameter.getMessage());
        dynamicContext.setMaxStep(requestParameter.getMaxStep() != null ? requestParameter.getMaxStep() : dynamicContext.getMaxStep());

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> get(ExecuteCommandEntity requestParameter, DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext) throws Exception {
        return step1McpToolsAnalysisNode;
    }

}
