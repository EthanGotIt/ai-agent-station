package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Flow Plan 根节点
 */
@Slf4j
@Service("flowRootNode")
public class RootNode extends AbstractExecuteSupport {

    @Resource
    private Step1ToolCapabilityNode step1ToolCapabilityNode;

    @Override
    protected String doApply(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws Exception {
        log.info("Flow Plan 根节点开始初始化，aiAgentId：{}", requestParameter.getAiAgentId());

        AgentRunAggregate run = currentRun(executionContext);
        long startTime = markStepRunning(executionContext, "flow_root", "Flow 根节点初始化", 0, "SYSTEM", null);
        executionContext.setMaxStep(run.maxStepOrDefault(executionContext.getMaxStep()));

        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = repository.queryAiAgentClientFlowConfig(requestParameter.getAiAgentId());
        executionContext.setAiAgentClientFlowConfigVOMap(flowConfigMap);
        run.markRunning();
        syncRunState(executionContext);
        if (flowConfigMap == null || flowConfigMap.isEmpty()) {
            run.markFailed("智能体未配置 Flow 客户端，无法执行");
            syncRunState(executionContext);
            markStepFailed(executionContext, "flow_root", "智能体未配置 Flow 客户端，无法执行", startTime);
            sendErrorResult(executionContext, "智能体未配置 Flow 客户端，无法执行");
            return "智能体未配置 Flow 客户端";
        }
        markStepSuccess(executionContext, "flow_root", "Flow 根节点初始化完成", startTime);

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = executionContext.getAiAgentClientFlowConfigVOMap();
        if (flowConfigMap == null || flowConfigMap.isEmpty()) {
            return defaultStrategyHandler;
        }
        return step1ToolCapabilityNode;
    }
}
