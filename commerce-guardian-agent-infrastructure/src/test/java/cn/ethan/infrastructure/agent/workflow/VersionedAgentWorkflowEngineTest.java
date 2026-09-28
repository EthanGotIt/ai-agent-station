package cn.ethan.infrastructure.agent.workflow;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnInputKindEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentWorkflowDecisionInput;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型职责：验证新催发货路由和历史 Workflow 恢复都遵循各自的持久化版本。
 *
 * @author ethan
 * @date 2026-09-24
 */
class VersionedAgentWorkflowEngineTest {

    private static final Instant NOW = Instant.parse("2026-09-24T00:00:00Z");

    @Test
    void javaModeSelectsOnlyNewExpediteRuns() {
        RecordingEngine legacy = new RecordingEngine("legacy");
        RecordingEngine java = new RecordingEngine("java");
        RunStore runs = new RunStore();
        VersionedAgentWorkflowEngine engine = new VersionedAgentWorkflowEngine(legacy, java, runs, "JAVA", false);

        assertEquals("java", engine.start(thread(), turn("turn-1"), "ORDER_SERVICE",
                Map.of("intent", "EXPEDITE")).runId());
        assertEquals("legacy", engine.start(thread(), turn("turn-2"), "ORDER_SERVICE",
                Map.of("intent", "REFUND")).runId());
        assertEquals(1, java.starts);
        assertEquals(1, legacy.starts);
    }

    @Test
    void resumeUsesPersistedRunVersionInsteadOfCurrentMode() {
        RecordingEngine legacy = new RecordingEngine("legacy");
        RecordingEngine java = new RecordingEngine("java");
        RunStore runs = new RunStore();
        runs.current = run(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_JAVA_V1);
        VersionedAgentWorkflowEngine engine = new VersionedAgentWorkflowEngine(legacy, java, runs, "OFF", false);
        AgentWorkflowDecisionInput input = new AgentWorkflowDecisionInput("run-1", "checkpoint-1", 0,
                AgentWorkflowDecisionEnum.REJECT, "fingerprint");
        AgentTurnModel decisionTurn = new AgentTurnModel("turn-2", "thread-1", "user-1", "request-2", "reject",
                AgentTurnStatusEnum.ACTIVE, 0, "run-1", null, NOW, NOW, null,
                null, 0, AgentTurnInputKindEnum.WORKFLOW_DECISION, null, null, input);

        assertEquals("java", engine.resume(thread(), decisionTurn, Map.of()).message());
        assertEquals(1, java.resumes);
        assertEquals(0, legacy.resumes);
    }

    @Test
    void existingLegacyRunStaysOnCompatibilityEngineWhenJavaModeIsEnabled() {
        RecordingEngine legacy = new RecordingEngine("legacy");
        RecordingEngine java = new RecordingEngine("java");
        RunStore runs = new RunStore();
        runs.current = run(AgentWorkflowOrchestrationVersionEnum.EXPEDITE_GRAPH_V2);
        VersionedAgentWorkflowEngine engine = new VersionedAgentWorkflowEngine(legacy, java, runs, "JAVA", false);

        assertEquals("legacy", engine.start(thread(), turn("turn-1"), "ORDER_SERVICE",
                Map.of("intent", "EXPEDITE")).runId());
        assertEquals(1, legacy.starts);
        assertEquals(0, java.starts);
    }

    private static AgentThreadModel thread() {
        return new AgentThreadModel("thread-1", "user-1", "订单", AgentThreadStatusEnum.ACTIVE,
                "ORDER", "ORDER-1", 0, NOW, NOW);
    }

    private static AgentTurnModel turn(String turnId) {
        return new AgentTurnModel(turnId, "thread-1", "user-1", "request-" + turnId, "催发货",
                AgentTurnStatusEnum.ACTIVE, 0, null, null, NOW, NOW, null);
    }

    private static AgentWorkflowRunModel run(AgentWorkflowOrchestrationVersionEnum version) {
        return new AgentWorkflowRunModel("run-1", "thread-1", "turn-1", "user-1",
                AgentWorkflowTypeEnum.ORDER_SERVICE, AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0,
                "[]", "{}", NOW, NOW, version);
    }

    private static final class RunStore implements AgentWorkflowRunStore {
        private AgentWorkflowRunModel current;

        @Override public void create(AgentWorkflowRunModel run) { current = run; }
        @Override public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
            return current != null && current.userId().equals(userId) && current.runId().equals(runId)
                    ? Optional.of(current) : Optional.empty();
        }
        @Override public Optional<AgentWorkflowRunModel> findBySource(String userId, String turnId,
                                                                      AgentWorkflowTypeEnum type) {
            return current != null && current.userId().equals(userId) && current.turnId().equals(turnId)
                    && current.workflowType() == type ? Optional.of(current) : Optional.empty();
        }
        @Override public void update(AgentWorkflowRunModel run) { current = run; }
    }

    private static final class RecordingEngine implements AgentWorkflowEngine {
        private final String name;
        private int starts;
        private int resumes;

        private RecordingEngine(String name) { this.name = name; }

        @Override public StartResult start(AgentThreadModel thread, AgentTurnModel turn, String operation,
                                           Map<String, String> arguments) {
            starts++;
            return new StartResult(name, null, null);
        }

        @Override public ResumeResult resume(AgentThreadModel thread, AgentTurnModel turn,
                                             Map<String, String> answers) {
            resumes++;
            return new ResumeResult(name, "RESUMED", null);
        }
    }
}
