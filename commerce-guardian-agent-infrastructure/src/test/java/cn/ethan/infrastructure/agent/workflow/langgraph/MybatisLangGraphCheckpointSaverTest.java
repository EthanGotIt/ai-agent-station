package cn.ethan.infrastructure.agent.workflow.langgraph;

import cn.ethan.infrastructure.agent.workflow.persistence.AgentGraphSnapshotEntity;
import cn.ethan.infrastructure.agent.workflow.persistence.AgentGraphSnapshotMapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证 MyBatis Checkpoint Saver 的 runId、版本、指纹和覆盖恢复契约。
 *
 * @author ethan
 * @date 2026-08-27
 */
class MybatisLangGraphCheckpointSaverTest {

    @Test
    void savesReadsAndOverwritesTechnicalSnapshotWithoutBusinessDecision() throws Exception {
        State state = new State();
        MybatisLangGraphCheckpointSaver saver = new MybatisLangGraphCheckpointSaver(
                mapper(state), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));
        RunnableConfig config = RunnableConfig.builder().threadId("run-1").build()
                .updateMetadata(Map.of("workflowVersion", 3L,
                        "factsFingerprint", "facts-v1",
                        "orchestrationVersion", "EXPEDITE_GRAPH_V1"));
        Checkpoint first = checkpoint("checkpoint-1", "VERIFY_FACTS", "SWITCH_REQUIREMENTS", 3L, "facts-v1");

        RunnableConfig returned = saver.put(config, first);

        assertEquals("run-1", returned.threadId().orElseThrow());
        assertEquals("checkpoint-1", returned.checkPointId().orElseThrow());
        assertEquals("run-1", state.entity.getRunId());
        assertEquals(3L, state.entity.getWorkflowVersion());
        assertEquals("facts-v1", state.entity.getFactsFingerprint());
        assertEquals("EXPEDITE_GRAPH_V1", state.entity.getOrchestrationVersion());
        assertEquals("VERIFY_FACTS", saver.get(config).orElseThrow().getNodeId());

        Checkpoint replacement = checkpoint("checkpoint-1", "AUTHORIZE", "EXECUTE_ACTION", 4L, "facts-v2");
        RunnableConfig replacementConfig = returned.updateMetadata(Map.of("workflowVersion", 4L,
                "factsFingerprint", "facts-v2", "orchestrationVersion", "EXPEDITE_GRAPH_V1"));
        saver.put(replacementConfig, replacement);

        assertEquals(1, state.entities.size());
        assertEquals("AUTHORIZE", saver.get(replacementConfig).orElseThrow().getNodeId());
        assertEquals(4L, state.entity.getWorkflowVersion());
        assertTrue(saver.list(replacementConfig).stream().anyMatch(item -> "EXECUTE_ACTION".equals(item.getNextNodeId())));
    }

    @Test
    void rejectsSnapshotWhenRequestedBusinessMetadataDoesNotMatch() throws Exception {
        State state = new State();
        MybatisLangGraphCheckpointSaver saver = new MybatisLangGraphCheckpointSaver(
                mapper(state), new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC));
        RunnableConfig valid = RunnableConfig.builder().threadId("run-1").build()
                .updateMetadata(Map.of("workflowVersion", 3L,
                        "factsFingerprint", "facts-v1",
                        "orchestrationVersion", "EXPEDITE_GRAPH_V1"));
        saver.put(valid, checkpoint("checkpoint-1", "VERIFY_FACTS", "SWITCH_REQUIREMENTS", 3L, "facts-v1"));

        RunnableConfig changedFacts = RunnableConfig.builder().threadId("run-1").build()
                .updateMetadata(Map.of("workflowVersion", 4L,
                        "factsFingerprint", "facts-v2",
                        "orchestrationVersion", "EXPEDITE_GRAPH_V1"));

        assertTrue(saver.get(changedFacts).isEmpty());
        assertTrue(saver.list(changedFacts).isEmpty());
    }

    private Checkpoint checkpoint(String id, String node, String next, long version, String fingerprint) {
        return Checkpoint.builder().id(id).nodeId(node).nextNodeId(next)
                .state(Map.of("workflowVersion", version, "factsFingerprint", fingerprint,
                        "orchestrationVersion", "EXPEDITE_GRAPH_V1", "orderId", "order-1"))
                .build();
    }

    private AgentGraphSnapshotMapper mapper(State state) {
        return (AgentGraphSnapshotMapper) Proxy.newProxyInstance(
                AgentGraphSnapshotMapper.class.getClassLoader(),
                new Class<?>[]{AgentGraphSnapshotMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "selectByGraphThreadId" -> new ArrayList<>(state.entities);
                    case "selectByGraphThreadAndCheckpoint" -> state.entity;
                    case "insert" -> {
                        state.entity = (AgentGraphSnapshotEntity) arguments[0];
                        state.entities.add(state.entity);
                        yield 1;
                    }
                    case "updateById" -> {
                        AgentGraphSnapshotEntity replacement = (AgentGraphSnapshotEntity) arguments[0];
                        state.entity = replacement;
                        state.entities.set(0, replacement);
                        yield 1;
                    }
                    case "toString" -> "AgentGraphSnapshotMapperTestProxy";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        if (List.class.isAssignableFrom(type)) return List.of();
        return null;
    }

    private static final class State {
        private final List<AgentGraphSnapshotEntity> entities = new ArrayList<>();
        private AgentGraphSnapshotEntity entity;
    }
}
