package cn.ethan.infrastructure.agent.coordination.springai;

import cn.ethan.core.agent.action.ExternalActionCommandModel;
import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.action.ExternalActionStatusEnum;
import cn.ethan.core.agent.action.ExternalActionTypeEnum;
import cn.ethan.core.agent.execution.AgentRuntimeMetrics;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证新 Agent Turn 从持久化 Run/Command 获取最新事项状态。
 *
 * @author ethan
 * @date 2026-09-28
 */
class LatestWorkflowFactsTest {

    @Test
    void injectsCurrentCommandStateWithoutTreatingAcceptanceAsSuccess() {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        AgentWorkflowRunModel run = new AgentWorkflowRunModel("run-1", "thread-1", "turn-1", "user-1",
                AgentWorkflowTypeEnum.ORDER_SERVICE, AgentWorkflowStatusEnum.WAITING_EXTERNAL_ACTION,
                1, "[]", "{\"intent\":\"REFUND\",\"orderId\":\"ORDER-1\"}", now, now,
                AgentWorkflowOrchestrationVersionEnum.REFUND_JAVA_V1);
        ExternalActionCommandModel command = new ExternalActionCommandModel("action-1", "run-1", "thread-1",
                "turn-1", "user-1", ExternalActionTypeEnum.REFUND, "key-1",
                "{\"orderId\":\"ORDER-1\"}", ExternalActionStatusEnum.PENDING, 0, 3,
                now, null, null, null, null, now, now, null);
        AgentWorkflowRunStore runs = new AgentWorkflowRunStore() {
            @Override public void create(AgentWorkflowRunModel value) { }
            @Override public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
                return Optional.of(run);
            }
            @Override public List<AgentWorkflowRunModel> findRecent(String userId, String threadId, int limit) {
                return List.of(run);
            }
            @Override public void update(AgentWorkflowRunModel value) { }
        };
        ExternalActionCommandStore commands = new ExternalActionCommandStore() {
            @Override public ExternalActionCommandModel createIfAbsent(ExternalActionCommandModel value) {
                return value;
            }
            @Override public Optional<ExternalActionCommandModel> findById(String userId, String commandId) {
                return Optional.empty();
            }
            @Override public Optional<ExternalActionCommandModel> findByRunId(String userId, String runId) {
                return Optional.of(command);
            }
            @Override public Optional<ExternalActionCommandModel> findByIdempotencyKey(String userId, String key) {
                return Optional.empty();
            }
            @Override public List<ExternalActionCommandModel> claimDue(Instant current, Instant leaseUntil,
                                                                       String workerId, int limit) {
                return List.of();
            }
            @Override public boolean update(ExternalActionCommandModel expected,
                                            ExternalActionCommandModel next) {
                return false;
            }
        };
        SpringAiAgentTurnCoordinator coordinator = new SpringAiAgentTurnCoordinator(
                null, null, null, null, null, null, Clock.fixed(now, ZoneOffset.UTC),
                AgentRuntimeMetrics.noop(), null, null, null, 3, 8_000, runs, commands);
        AgentThreadModel thread = new AgentThreadModel("thread-1", "user-1", "订单",
                AgentThreadStatusEnum.ACTIVE, "ORDER", "ORDER-1", 0, now, now);

        String facts = coordinator.latestWorkflowFacts(thread);
        assertTrue(facts.contains("Workflow WAITING_EXTERNAL_ACTION"));
        assertTrue(facts.contains("Command PENDING"));
        assertTrue(facts.contains("命令受理不等于业务成功"));
    }
}
