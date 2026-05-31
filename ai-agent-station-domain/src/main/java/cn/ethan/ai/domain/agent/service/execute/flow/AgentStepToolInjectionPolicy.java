package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.PlanStepTypeEnumVO;

/**
 * Flow Plan 步骤级外部 MCP 工具注入策略。
 */
public final class AgentStepToolInjectionPolicy {

    private AgentStepToolInjectionPolicy() {
    }

    public static boolean shouldInjectExternalMcpTools(AgentPlanStepVO step,
                                                       ToolRoutingDecisionVO routingDecision) {
        if (step == null || routingDecision == null || !routingDecision.isEnabled()) {
            return false;
        }
        return PlanStepTypeEnumVO.LLM.name().equalsIgnoreCase(step.getType())
                || PlanStepTypeEnumVO.TOOL.name().equalsIgnoreCase(step.getType());
    }

}
