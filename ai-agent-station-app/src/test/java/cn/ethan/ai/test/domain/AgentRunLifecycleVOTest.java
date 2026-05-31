package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentRunLifecycleVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class AgentRunLifecycleVOTest {

    @Test
    public void shouldExposePlanningPhaseWhenPlanGenerationRunning() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.RUNNING,
                null,
                null,
                0,
                0,
                "",
                List.of(step("flow_plan_generate", "结构化计划生成", AgentStepRunStatusEnumVO.RUNNING, null, null))
        );

        Assert.assertEquals("PLANNING", lifecycle.getRuntimePhase());
        Assert.assertEquals("flow_plan_generate", lifecycle.getCurrentStepId());
        Assert.assertEquals(Integer.valueOf(1), lifecycle.getTrackedStepCount());
    }

    @Test
    public void shouldExposePlanValidationFailureReason() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.FAILED,
                "执行计划校验失败：重复的 stepId",
                null,
                0,
                0,
                "",
                List.of(step("flow_plan_validate", "执行计划校验", AgentStepRunStatusEnumVO.FAILED, null, "重复的 stepId"))
        );

        Assert.assertEquals("FAILED", lifecycle.getRuntimePhase());
        Assert.assertTrue(lifecycle.getTerminalReason().contains("执行计划校验"));
        Assert.assertTrue(lifecycle.getTerminalReason().contains("重复的 stepId"));
        Assert.assertEquals(Integer.valueOf(1), lifecycle.getFailedStepCount());
    }

    @Test
    public void shouldExposeExecutionFailureReason() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.FAILED,
                "LLM_CALL_STEP 调用失败",
                null,
                0,
                0,
                "",
                List.of(step("step_1", "执行步骤", AgentStepRunStatusEnumVO.FAILED, null, "模型调用超时"))
        );

        Assert.assertEquals("FAILED", lifecycle.getRuntimePhase());
        Assert.assertTrue(lifecycle.getTerminalReason().contains("模型调用超时"));
    }

    @Test
    public void shouldExposeCancelAndSkippedCounters() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.CANCELLED,
                null,
                "用户主动取消",
                0,
                0,
                "",
                List.of(
                        step("step_1", "已取消步骤", AgentStepRunStatusEnumVO.CANCELLED, "任务已取消", null),
                        step("step_2", "跳过步骤", AgentStepRunStatusEnumVO.SKIPPED, "上下文预算达到终止阈值", null)
                )
        );

        Assert.assertEquals("CANCELLED", lifecycle.getRuntimePhase());
        Assert.assertEquals("用户主动取消", lifecycle.getTerminalReason());
        Assert.assertEquals(Integer.valueOf(1), lifecycle.getCancelledStepCount());
        Assert.assertEquals(Integer.valueOf(1), lifecycle.getSkippedStepCount());
    }

    @Test
    public void shouldExposeContextCompacted() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.RUNNING,
                null,
                null,
                3000,
                1200,
                "history summary",
                List.of()
        );

        Assert.assertEquals("RUNNING", lifecycle.getRuntimePhase());
        Assert.assertTrue(lifecycle.getContextCompacted());
    }

    private AgentStepRunRecordVO step(String stepId,
                                      String stepName,
                                      AgentStepRunStatusEnumVO status,
                                      String outputSummary,
                                      String errorMessage) {
        return AgentStepRunRecordVO.builder()
                .stepId(stepId)
                .stepName(stepName)
                .status(status)
                .outputSummary(outputSummary)
                .errorMessage(errorMessage)
                .build();
    }
}
