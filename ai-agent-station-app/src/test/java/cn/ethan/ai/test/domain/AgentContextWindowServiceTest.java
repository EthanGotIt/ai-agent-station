package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentContextBoundaryVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.ContextGuardResultVO;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextBoundaryService;
import cn.ethan.ai.domain.agent.service.execute.flow.AgentContextWindowService;
import org.junit.Assert;
import org.junit.Test;

public class AgentContextWindowServiceTest {

    private final AgentContextWindowService agentContextWindowService = new AgentContextWindowService();

    private final AgentContextBoundaryService agentContextBoundaryService = new AgentContextBoundaryService();

    @Test
    public void shouldKeepOriginalOutputsWhenBelowThreshold() {
        AgentRunAggregate run = AgentRunAggregate.create(
                ExecuteCommandEntity.builder()
                        .aiAgentId("1")
                        .message("test")
                        .sessionId("session-1")
                        .maxStep(3)
                        .build(),
                ContextBudgetPolicyVO.builder()
                        .maxChars(1000)
                        .compressThreshold(0.8D)
                        .summaryMaxChars(200)
                        .build()
        );
        run.recordStepOutput("step_1", "简短输出");

        ContextGuardResultVO result = agentContextWindowService.prepareStepOutputs(run);
        Assert.assertFalse(result.isCompressed());
        Assert.assertEquals(run.stepOutputs(), result.getStepOutputs());
    }

    @Test
    public void shouldCompressHistoryWhenOutputTooLong() {
        AgentRunAggregate run = AgentRunAggregate.create(
                ExecuteCommandEntity.builder()
                        .aiAgentId("1")
                        .message("test")
                        .sessionId("session-2")
                        .maxStep(3)
                        .build(),
                ContextBudgetPolicyVO.builder()
                        .maxChars(600)
                        .compressThreshold(0.5D)
                        .summaryMaxChars(180)
                        .build()
        );
        run.recordStepOutput("step_1", "甲".repeat(360));
        run.recordStepOutput("step_2", "乙".repeat(360));
        run.recordStepOutput("step_3", "丙".repeat(360));

        ContextGuardResultVO result = agentContextWindowService.prepareStepOutputs(run);
        Assert.assertTrue(result.isCompressed());
        Assert.assertTrue(result.getCompressedChars() < result.getOriginalChars());
        Assert.assertTrue(result.getStepOutputs().containsKey("history_summary"));
        Assert.assertTrue(run.getContextWindowGuard().isHistoryCompacted());
    }

    @Test
    public void shouldContinueWithCompactedSummaryAfterCompression() {
        AgentRunAggregate run = AgentRunAggregate.create(
                ExecuteCommandEntity.builder()
                        .aiAgentId("1")
                        .message("test")
                        .sessionId("session-3")
                        .maxStep(4)
                        .build(),
                ContextBudgetPolicyVO.builder()
                        .maxChars(600)
                        .compressThreshold(0.5D)
                        .summaryMaxChars(220)
                        .build()
        );
        run.recordStepOutput("step_1", "甲".repeat(360));
        run.recordStepOutput("step_2", "乙".repeat(360));
        run.recordStepOutput("step_3", "丙".repeat(360));

        ContextGuardResultVO firstResult = agentContextWindowService.prepareStepOutputs(run);
        AgentContextBoundaryVO boundary = agentContextBoundaryService.buildBoundary(run.getCommand());
        AgentContextBoundaryService.attachRunSummary(boundary, firstResult.getHistorySummary());
        run.recordStepOutput("step_4", "压缩后继续执行的步骤输出");

        ContextGuardResultVO nextResult = agentContextWindowService.prepareStepOutputs(run);

        Assert.assertTrue(firstResult.isCompressed());
        Assert.assertTrue(nextResult.isCompressed());
        Assert.assertTrue(nextResult.getStepOutputs().containsKey("history_summary"));
        Assert.assertTrue(nextResult.getStepOutputs().containsKey("step_4"));
        Assert.assertTrue(AgentContextBoundaryService.buildPromptSection(boundary).contains("以下为历史步骤摘要"));
    }

}
