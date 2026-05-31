package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.ContextBudgetPolicyVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import org.junit.Assert;
import org.junit.Test;

public class AgentRunAggregateTest {

    @Test
    public void shouldConvertRunStateToRecord() {
        AgentRunAggregate run = AgentRunAggregate.create(
                ExecuteCommandEntity.builder()
                        .aiAgentId("1")
                        .message("hello")
                        .sessionId("session-x")
                        .maxStep(3)
                        .build(),
                ContextBudgetPolicyVO.builder().build()
        );
        run.markRunning();
        run.bindSessionContextSummary("previous session summary");
        run.getContextWindowGuard().updateHistorySnapshot(3000, 1200, "history summary");
        run.markSuccess("final summary");

        Assert.assertEquals(AgentRunStatusEnumVO.SUCCESS, run.toRecord().getStatus());
        Assert.assertEquals("final summary", run.toRecord().getFinalSummary());
        Assert.assertEquals(Integer.valueOf(3000), run.toRecord().getContextOriginalChars());
        Assert.assertEquals(Integer.valueOf(1200), run.toRecord().getContextCompressedChars());
        Assert.assertEquals("previous session summary", run.toRecord().getSessionContextSummary());
    }

    @Test
    public void shouldExposeFailedAndCancelledTerminalState() {
        AgentRunAggregate failedRun = AgentRunAggregate.create(
                command(),
                ContextBudgetPolicyVO.builder().build()
        );
        failedRun.markRunning();
        failedRun.markFailed("计划校验失败");

        Assert.assertEquals(AgentRunStatusEnumVO.FAILED, failedRun.toRecord().getStatus());
        Assert.assertEquals("计划校验失败", failedRun.toRecord().getErrorMessage());

        AgentRunAggregate cancelledRun = AgentRunAggregate.create(
                command(),
                ContextBudgetPolicyVO.builder().build()
        );
        cancelledRun.markRunning();
        cancelledRun.markCancelled("用户主动取消");

        Assert.assertEquals(AgentRunStatusEnumVO.CANCELLED, cancelledRun.toRecord().getStatus());
        Assert.assertEquals("用户主动取消", cancelledRun.toRecord().getCancelReason());
    }

    private ExecuteCommandEntity command() {
        return ExecuteCommandEntity.builder()
                .aiAgentId("1")
                .message("hello")
                .sessionId("session-x")
                .maxStep(3)
                .build();
    }

}
