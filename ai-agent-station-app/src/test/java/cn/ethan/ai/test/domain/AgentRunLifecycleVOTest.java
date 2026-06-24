package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.valobj.AgentRunLifecycleVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class AgentRunLifecycleVOTest {

    @Test
    public void shouldExposeDecidingPhaseWhenHarnessActionRunning() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.RUNNING,
                null,
                null,
                List.of(step("harness_action_1", "Harness Action 1", AgentStepRunStatusEnumVO.RUNNING, null, null))
        );

        Assertions.assertEquals("DECIDING", lifecycle.getRuntimePhase());
        Assertions.assertEquals("harness_action_1", lifecycle.getCurrentStepId());
        Assertions.assertEquals(Integer.valueOf(1), lifecycle.getTrackedStepCount());
    }

    @Test
    public void shouldExposeHarnessActionFailureReason() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.FAILED,
                "Action 类型为空，拒绝执行。",
                null,
                List.of(step("harness_action_1", "Harness Action 1", AgentStepRunStatusEnumVO.FAILED, null, "Action 类型为空"))
        );

        Assertions.assertEquals("FAILED", lifecycle.getRuntimePhase());
        Assertions.assertTrue(lifecycle.getTerminalReason().contains("Harness Action 1"));
        Assertions.assertTrue(lifecycle.getTerminalReason().contains("Action 类型为空"));
        Assertions.assertEquals(Integer.valueOf(1), lifecycle.getFailedStepCount());
    }

    @Test
    public void shouldExposeExecutionFailureReason() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.FAILED,
                "LLM_CALL_STEP 调用失败",
                null,
                List.of(step("step_1", "执行步骤", AgentStepRunStatusEnumVO.FAILED, null, "模型调用超时"))
        );

        Assertions.assertEquals("FAILED", lifecycle.getRuntimePhase());
        Assertions.assertTrue(lifecycle.getTerminalReason().contains("模型调用超时"));
    }

    @Test
    public void shouldExposeCancelAndSkippedCounters() {
        AgentRunLifecycleVO lifecycle = AgentRunLifecycleVO.from(
                AgentRunStatusEnumVO.CANCELLED,
                null,
                "用户主动取消",
                List.of(
                        step("step_1", "已取消步骤", AgentStepRunStatusEnumVO.CANCELLED, "任务已取消", null),
                        step("step_2", "跳过步骤", AgentStepRunStatusEnumVO.SKIPPED, "上下文预算达到终止阈值", null)
                )
        );

        Assertions.assertEquals("CANCELLED", lifecycle.getRuntimePhase());
        Assertions.assertEquals("用户主动取消", lifecycle.getTerminalReason());
        Assertions.assertEquals(Integer.valueOf(1), lifecycle.getCancelledStepCount());
        Assertions.assertEquals(Integer.valueOf(1), lifecycle.getSkippedStepCount());
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
