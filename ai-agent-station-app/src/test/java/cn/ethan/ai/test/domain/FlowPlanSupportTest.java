package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanValidationResultVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanParser;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanValidator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class FlowPlanSupportTest {

    private final AgentPlanParser parser = new AgentPlanParser();

    private final AgentPlanValidator validator = new AgentPlanValidator();

    @Test
    public void parseValidJsonPlan() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "search",
                      "type": "TOOL",
                      "toolName": "search",
                      "input": {"keyword": "spring ai"},
                      "dependsOn": [],
                      "successCriteria": "has result"
                    }
                  ]
                }
                """);

        Assert.assertEquals("test", plan.getGoal());
        Assert.assertEquals(1, plan.getSteps().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectInvalidJsonPlan() {
        parser.parse("not json");
    }

    @Test
    public void rejectUnknownDependency() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "summarize",
                      "type": "LLM",
                      "toolName": "",
                      "input": {},
                      "dependsOn": ["missing"],
                      "successCriteria": "has summary"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3, Set.of("search"));
        Assert.assertFalse(result.isValid());
        Assert.assertTrue(result.formatErrors().contains("missing"));
    }

    @Test
    public void rejectIllegalTool() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "danger",
                      "type": "TOOL",
                      "toolName": "delete_everything",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3, Set.of("search"));
        Assert.assertFalse(result.isValid());
        Assert.assertTrue(result.formatErrors().contains("不在白名单"));
    }

    @Test
    public void rejectDuplicateStepIdAndExceededMaxStep() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "one",
                      "type": "LLM",
                      "toolName": "",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    },
                    {
                      "stepId": "step_1",
                      "name": "two",
                      "type": "LLM",
                      "toolName": "",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 1, Set.of());
        Assert.assertFalse(result.isValid());
        Assert.assertTrue(result.formatErrors().contains("重复的 stepId"));
        Assert.assertTrue(result.formatErrors().contains("超过 maxStep"));
    }

    @Test
    public void rejectEmptySteps() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": []
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3, Set.of());
        Assert.assertFalse(result.isValid());
        Assert.assertTrue(result.formatErrors().contains("步骤为空"));
    }

    @Test
    public void contextWindowGuardThresholds() {
        ContextWindowGuardVO contextWindowGuard = new ContextWindowGuardVO();
        contextWindowGuard.record("a".repeat(38400));
        Assert.assertTrue(contextWindowGuard.shouldCompactHistory());
        contextWindowGuard.markHistoryCompacted();
        contextWindowGuard.record("b".repeat(7200));
        Assert.assertTrue(contextWindowGuard.shouldStopNewLlmCall());
    }

    @Test
    public void contextWindowGuardEstimateChineseMoreConservatively() {
        ContextWindowGuardVO contextWindowGuard = new ContextWindowGuardVO();
        Assert.assertTrue(contextWindowGuard.estimate("中文上下文保护") >= 6);
        Assert.assertTrue(contextWindowGuard.estimate("context guard") < contextWindowGuard.estimate("中文上下文保护"));
    }
}
