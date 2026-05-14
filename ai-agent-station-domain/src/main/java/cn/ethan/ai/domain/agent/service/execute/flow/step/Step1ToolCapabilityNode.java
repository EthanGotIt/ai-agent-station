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

import java.util.LinkedHashMap;
import java.util.Map;

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
        if (stopIfCancelled(executionContext, "任务已取消，停止执行工具路由。")) {
            return "任务已取消";
        }
        long startTime = markStepRunning(executionContext, "flow_tool_routing", "运行时工具路由", 1, "SYSTEM", null);

        try {
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
                    buildRoutingPayload(toolRoutingDecision),
                    requestParameter.getSessionId(),
                    run.runId()
            ));
            markStepSuccess(executionContext, "flow_tool_routing", toolRoutingDecision.getSummary(), startTime);
        } catch (Exception e) {
            markStepFailed(executionContext, "flow_tool_routing", e.getMessage(), startTime);
            throw e;
        }

        return router(requestParameter, executionContext);
    }

    @Override
    public StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> get(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) {
        if (executionContext.isCancelled()) {
            return defaultStrategyHandler;
        }
        return step2PlanGenerateNode;
    }

    private Map<String, Object> buildRoutingPayload(ToolRoutingDecisionVO toolRoutingDecision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("enabled", toolRoutingDecision.isEnabled());
        payload.put("summary", toolRoutingDecision.getSummary());
        payload.put("routeReason", toolRoutingDecision.getSummary());
        payload.put("disabledReason", toolRoutingDecision.isEnabled() ? "" : toolRoutingDecision.getSummary());
        payload.put("selectedCount", toolRoutingDecision.getSelectedTools() == null ? 0 : toolRoutingDecision.getSelectedTools().size());
        payload.put("allowedToolNames", toolRoutingDecision.getAllowedToolNames());
        payload.put("selectedMcpIds", toolRoutingDecision.getSelectedMcpIds());
        payload.put("selectedTools", toolRoutingDecision.getSelectedTools());
        return payload;
    }
}

