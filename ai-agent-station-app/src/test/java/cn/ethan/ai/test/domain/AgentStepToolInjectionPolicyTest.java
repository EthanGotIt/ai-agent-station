package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.ToolRoutingDecisionVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentStepToolInjectionPolicy;
import org.junit.Assert;
import org.junit.Test;

public class AgentStepToolInjectionPolicyTest {

    @Test
    public void shouldInjectToolsForLlmAndToolStepsWhenRoutingEnabled() {
        ToolRoutingDecisionVO routingDecision = ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary("enabled")
                .build();

        Assert.assertTrue(AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step("LLM"), routingDecision));
        Assert.assertTrue(AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step("TOOL"), routingDecision));
    }

    @Test
    public void shouldNotInjectToolsForRagSupervisionSummaryOrDisabledRouting() {
        ToolRoutingDecisionVO enabledRouting = ToolRoutingDecisionVO.builder()
                .enabled(true)
                .summary("enabled")
                .build();
        ToolRoutingDecisionVO disabledRouting = ToolRoutingDecisionVO.disabled("disabled");

        Assert.assertFalse(AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step("RAG"), enabledRouting));
        Assert.assertFalse(AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step("SUPERVISION"), enabledRouting));
        Assert.assertFalse(AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step("SUMMARY"), enabledRouting));
        Assert.assertFalse(AgentStepToolInjectionPolicy.shouldInjectExternalMcpTools(step("LLM"), disabledRouting));
    }

    private AgentPlanStepVO step(String type) {
        return AgentPlanStepVO.builder()
                .stepId("step_1")
                .name("test")
                .type(type)
                .build();
    }
}
