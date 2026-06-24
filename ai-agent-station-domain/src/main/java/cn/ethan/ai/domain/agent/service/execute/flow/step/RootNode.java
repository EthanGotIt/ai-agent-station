package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextBoundaryService;
import cn.ethan.ai.types.enums.StepIdEnum;
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
        long startTime = markStepRunning(executionContext, StepIdEnum.FLOW_ROOT.value(), "Flow 根节点初始化", 0, "SYSTEM");
        executionContext.setMaxStep(run.maxStepOrDefault(executionContext.getMaxStep()));

        Map<String, AiAgentClientFlowConfigVO> flowConfigMap = repository.queryAiAgentClientFlowConfig(requestParameter.getAiAgentId());
        executionContext.setAiAgentClientFlowConfigVOMap(flowConfigMap);
        run.markRunning();
        syncRunState(executionContext);
        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                0,
                "context_boundary",
                "上下文边界已绑定，本轮持久化短期记忆、用户偏好和 Run 摘要均按 session 隔离。",
                AgentContextBoundaryService.buildPayload(executionContext.getContextBoundary()),
                requestParameter.getSessionId(),
                run.runId()
        ));
        if (flowConfigMap == null || flowConfigMap.isEmpty()) {
            run.markFailed("智能体未配置 Flow 客户端，无法执行");
            syncRunState(executionContext);
            markStepFailed(executionContext, StepIdEnum.FLOW_ROOT.value(), "智能体未配置 Flow 客户端，无法执行", startTime);
            sendErrorResult(executionContext, "智能体未配置 Flow 客户端，无法执行");
            return "智能体未配置 Flow 客户端";
        }
        markStepSuccess(executionContext, StepIdEnum.FLOW_ROOT.value(), "Flow 根节点初始化完成", startTime);

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
