package cn.ethan.ai.test.domain;

import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AgentRunAggregateTest {

    @Test
    public void shouldConvertRunStateToRecord() {
        AgentRunAggregate run = AgentRunAggregate.create(
                ExecuteCommandEntity.builder()
                        .aiAgentId("agent-java-knowledge")
                        .message("hello")
                        .sessionId("session-x")
                        .maxStep(3)
                        .build()
        );
        run.markRunning();
        run.bindSessionContextSummary("previous session summary");
        run.markSuccess("final summary");

        Assertions.assertEquals(AgentRunStatusEnumVO.SUCCESS, run.toRecord().getStatus());
        Assertions.assertEquals("final summary", run.toRecord().getFinalSummary());
        Assertions.assertEquals("previous session summary", run.toRecord().getSessionContextSummary());
    }

    @Test
    public void shouldExposeFailedAndCancelledTerminalState() {
        AgentRunAggregate failedRun = AgentRunAggregate.create(
                command()
        );
        failedRun.markRunning();
        failedRun.markFailed("计划校验失败");

        Assertions.assertEquals(AgentRunStatusEnumVO.FAILED, failedRun.toRecord().getStatus());
        Assertions.assertEquals("计划校验失败", failedRun.toRecord().getErrorMessage());

        AgentRunAggregate cancelledRun = AgentRunAggregate.create(
                command()
        );
        cancelledRun.markRunning();
        cancelledRun.markCancelled("用户主动取消");

        Assertions.assertEquals(AgentRunStatusEnumVO.CANCELLED, cancelledRun.toRecord().getStatus());
        Assertions.assertEquals("用户主动取消", cancelledRun.toRecord().getCancelReason());
    }

    private ExecuteCommandEntity command() {
        return ExecuteCommandEntity.builder()
                .aiAgentId("agent-java-knowledge")
                .message("hello")
                .sessionId("session-x")
                .maxStep(3)
                .build();
    }

}
