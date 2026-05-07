package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.service.execute.flow.FlowToolCapabilityService;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 步骤1：运行期工具动态路由。
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
        log.info("步骤1：执行 MCP 动态工具路由");

        ToolRoutingDecisionVO toolRoutingDecision = flowToolCapabilityService.routeTools(
                executionContext.getAiAgentClientFlowConfigVOMap(),
                requestParameter.getMessage()
        );
        executionContext.setToolRoutingDecision(toolRoutingDecision);
        executionContext.setAllowedTools(toolRoutingDecision.getAllowedToolNames());
        executionContext.setToolCapabilitySummary(toolRoutingDecision.getSummary());

        AgentRunAggregate run = currentRun(executionContext);
        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                nextStreamStep(executionContext),
                "analysis_tools",
                toolRoutingDecision.getSummary(),
                toolRoutingDecision,
                requestParameter.getSessionId(),
                run.runId()
        ));
        sendStreamResult(executionContext, AgentExecuteResultEntity.createAnalysisSubResult(
                nextStreamStep(executionContext),
                "tool_routing",
                toolRoutingDecision.isEnabled() ? "本轮已完成工具筛选。" : "本轮未启用 MCP 工具。",
                toolRoutingDecision,
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

