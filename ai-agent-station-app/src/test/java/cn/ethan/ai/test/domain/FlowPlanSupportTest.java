package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanValidationResultVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextWindowGuardVO;
import cn.ethan.ai.domain.agent.model.valobj.HeuristicContextUnitEstimator;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanPromptFactory;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanParser;
import cn.ethan.ai.domain.agent.service.execute.flow.plan.AgentPlanValidator;
import org.junit.Assert;
import org.junit.Test;

import java.util.Set;

public class FlowPlanSupportTest {

    private final AgentPlanParser parser = new AgentPlanParser();

    private final AgentPlanValidator validator = new AgentPlanValidator();

    private final AgentPlanPromptFactory promptFactory = new AgentPlanPromptFactory();

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
    public void acceptToolStepWithoutPlanBoundTool() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "tool capable step",
                      "type": "TOOL",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3, Set.of("search"));
        Assert.assertTrue(result.formatErrors(), result.isValid());
    }

    @Test
    public void acceptRagStepWithoutToolWhitelist() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "knowledge lookup",
                      "type": "RAG",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "has evidence"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3, Set.of());
        Assert.assertTrue(result.formatErrors(), result.isValid());
    }

    @Test
    public void acceptToolStepWhenCurrentRoundHasNoToolBecauseExecutorMayRunWithoutTools() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "search",
                      "type": "TOOL",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3, Set.of());
        Assert.assertTrue(result.formatErrors(), result.isValid());
    }

    @Test
    public void acceptToolStepWithoutAllowedToolSet() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "tool capable step",
                      "type": "TOOL",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        AgentPlanValidationResultVO result = validator.validate(plan, 3);
        Assert.assertTrue(result.formatErrors(), result.isValid());
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
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    },
                    {
                      "stepId": "step_1",
                      "name": "two",
                      "type": "LLM",
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

    @Test
    public void heuristicContextUnitEstimatorShouldEstimateStableUnits() {
        HeuristicContextUnitEstimator estimator = HeuristicContextUnitEstimator.INSTANCE;
        Assert.assertEquals(0, estimator.estimate(""));
        Assert.assertTrue(estimator.estimate("中文") >= 2);
        Assert.assertTrue(estimator.estimate("context guard") < estimator.estimate("中文上下文保护"));
        Assert.assertTrue(estimator.estimate("🙂") >= 1);
    }

    @Test
    public void contextWindowGuardShouldUseInjectedEstimator() {
        ContextWindowGuardVO contextWindowGuard = new ContextWindowGuardVO(
                ContextBudgetPolicyVO.builder()
                        .maxChars(10)
                        .compressThreshold(0.8D)
                        .stopThreshold(0.95D)
                        .build(),
                text -> text == null ? 0 : text.length() * 2
        );

        contextWindowGuard.record("abcde");

        Assert.assertEquals(10, contextWindowGuard.getUsedContextUnits());
        Assert.assertTrue(contextWindowGuard.shouldStopNewLlmCall());
    }

    @Test
    public void planningPromptShouldNotBindConcreteTools() {
        String prompt = promptFactory.buildPlanningPrompt(
                ExecuteCommandEntity.builder()
                        .message("请调研 Spring AI MCP")
                        .maxStep(3)
                        .build(),
                "执行阶段工具策略摘要：本轮可能注入已授权工具。"
        );

        Assert.assertFalse(prompt.contains("只能使用工具能力摘要中的工具名称"));
        Assert.assertTrue(prompt.contains("不要在计划阶段提前绑定具体 MCP 工具"));
        Assert.assertTrue(prompt.contains("不输出 toolName 字段"));
    }

    @Test
    public void stepExecutionPromptShouldDescribeRuntimeToolUseAndFailureFallback() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "execute",
                      "type": "LLM",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        String prompt = promptFactory.buildStepExecutionPrompt(
                ExecuteCommandEntity.builder().message("test").build(),
                plan,
                plan.getSteps().get(0),
                java.util.Collections.emptyMap()
        );

        Assert.assertTrue(prompt.contains("系统按权限筛选并注入"));
        Assert.assertTrue(prompt.contains("不需要工具时直接完成"));
        Assert.assertTrue(prompt.contains("不要编造工具结果"));
    }

    @Test
    public void supervisionPromptShouldRequestJsonShape() {
        AgentPlanVO plan = parser.parse("""
                {
                  "goal": "test",
                  "steps": [
                    {
                      "stepId": "step_1",
                      "name": "execute",
                      "type": "LLM",
                      "input": {},
                      "dependsOn": [],
                      "successCriteria": "done"
                    }
                  ]
                }
                """);

        String prompt = promptFactory.buildSupervisionPrompt(
                ExecuteCommandEntity.builder().message("test").build(),
                plan,
                java.util.Collections.emptyMap()
        );

        Assert.assertTrue(prompt.contains("\"passed\""));
        Assert.assertTrue(prompt.contains("\"score\""));
        Assert.assertTrue(prompt.contains("\"issues\""));
        Assert.assertTrue(prompt.contains("\"suggestions\""));
        Assert.assertTrue(prompt.contains("\"reason\""));
    }
}
