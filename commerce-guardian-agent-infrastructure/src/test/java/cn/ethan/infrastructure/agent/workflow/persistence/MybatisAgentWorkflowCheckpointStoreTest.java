package cn.ethan.infrastructure.agent.workflow.persistence;

import cn.ethan.core.agent.thread.AgentInteractionTypeEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workflow Checkpoint 持久化测试：验证事实指纹变化会收口为 SUPERSEDED，且决策只清理匹配指针。
 *
 * @author ethan
 * @date 2026-08-27
 */
class MybatisAgentWorkflowCheckpointStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void createLocksThreadAndSetsCheckpointPointer() {
        State state = state(null, thread(null), 1);
        MybatisAgentWorkflowCheckpointStore store = store(state);

        store.create(checkpoint());

        assertEquals(List.of("thread.lock", "checkpoint.selectOpen", "checkpoint.insert", "thread.set"), state.calls);
        assertEquals(1, state.inserts.get());
        assertEquals(AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name(), state.thread.getOpenInteractionType());
    }

    @Test
    void changedFactsSupersedeCheckpointAndClearOpenPointer() {
        State state = state(checkpointEntity("OPEN", 0, null), thread("checkpoint-1"), 1);
        MybatisAgentWorkflowCheckpointStore store = store(state);

        assertFalse(store.decide("user-1", "checkpoint-1", 0, AgentWorkflowDecisionEnum.APPROVE, "facts-v2"));

        assertTrue(state.updates.get(0).getParamNameValuePairs().containsValue("SUPERSEDED"));
        assertEquals(1, state.pointerClears.get());
    }

    @Test
    void matchingFactsApproveOrRejectWithCas() {
        State state = state(checkpointEntity("OPEN", 0, null), thread("checkpoint-1"), 1, 1);
        MybatisAgentWorkflowCheckpointStore store = store(state);

        assertTrue(store.decide("user-1", "checkpoint-1", 0, AgentWorkflowDecisionEnum.APPROVE, "facts-v1"));
        assertTrue(store.decide("user-1", "checkpoint-1", 0, AgentWorkflowDecisionEnum.REJECT, "facts-v1"));
        assertTrue(state.updates.get(0).getParamNameValuePairs().containsValue("APPROVED"));
        assertTrue(state.updates.get(1).getParamNameValuePairs().containsValue("REJECTED"));
        assertEquals(2, state.pointerClears.get());
    }

    @Test
    void staleVersionDoesNotClearPointerOrWriteDecision() {
        State state = state(checkpointEntity("OPEN", 0, null), thread("checkpoint-1"), 0);
        MybatisAgentWorkflowCheckpointStore store = store(state);

        assertFalse(store.decide("user-1", "checkpoint-1", 0, AgentWorkflowDecisionEnum.APPROVE, "facts-v1"));
        assertEquals(0, state.pointerClears.get());
    }

    @Test
    void approvedCheckpointCanBeSupersededWithoutClearingAlreadyClosedPointer() {
        State state = state(checkpointEntity("APPROVED", 1, "APPROVE"), thread(null), 1);
        MybatisAgentWorkflowCheckpointStore store = store(state);

        assertTrue(store.supersede("user-1", "checkpoint-1", 1));

        assertTrue(state.updates.get(0).getParamNameValuePairs().containsValue("SUPERSEDED"));
        assertTrue(state.updates.get(0).getSqlSet().contains("DECISION = NULL"));
        assertEquals(0, state.pointerClears.get());
    }

    private AgentWorkflowCheckpointModel checkpoint() {
        return new AgentWorkflowCheckpointModel("checkpoint-1", "run-1", "thread-1", "turn-1", "user-1",
                "AUTHORIZE", "REFUND", "ORDER-1", "退款订单", "facts-v1", 0,
                AgentWorkflowCheckpointStatusEnum.OPEN, null, NOW, null);
    }

    private State state(AgentWorkflowCheckpointEntity checkpoint, AgentThreadEntity thread, int... results) {
        State state = new State();
        state.checkpoint = checkpoint;
        state.thread = thread;
        for (int result : results) {
            state.updateResults.add(result);
        }
        return state;
    }

    private AgentWorkflowCheckpointEntity checkpointEntity(String status, long version, String decision) {
        AgentWorkflowCheckpointEntity entity = new AgentWorkflowCheckpointEntity();
        entity.setCheckpointId("checkpoint-1");
        entity.setRunId("run-1");
        entity.setThreadId("thread-1");
        entity.setTurnId("turn-1");
        entity.setUserId("user-1");
        entity.setNodeId("AUTHORIZE");
        entity.setActionType("REFUND");
        entity.setOrderId("ORDER-1");
        entity.setImpactSummary("退款订单");
        entity.setFactsFingerprint("facts-v1");
        entity.setVersionNo(version);
        entity.setStatus(status);
        entity.setDecision(decision);
        entity.setCreatedAt(NOW);
        return entity;
    }

    private AgentThreadEntity thread(String openInteractionId) {
        AgentThreadEntity entity = new AgentThreadEntity();
        entity.setThreadId("thread-1");
        entity.setUserId("user-1");
        entity.setOpenInteractionId(openInteractionId);
        entity.setOpenInteractionType(openInteractionId == null ? null
                : AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name());
        return entity;
    }

    private MybatisAgentWorkflowCheckpointStore store(State state) {
        return new MybatisAgentWorkflowCheckpointStore(checkpointMapper(state), threadMapper(state),
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private AgentWorkflowCheckpointMapper checkpointMapper(State state) {
        return (AgentWorkflowCheckpointMapper) Proxy.newProxyInstance(
                AgentWorkflowCheckpointMapper.class.getClassLoader(),
                new Class<?>[]{AgentWorkflowCheckpointMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "selectById" -> state.checkpoint;
                    case "selectOpen" -> {
                        state.calls.add("checkpoint.selectOpen");
                        yield state.checkpoint;
                    }
                    case "insert" -> {
                        state.calls.add("checkpoint.insert");
                        state.inserts.incrementAndGet();
                        yield 1;
                    }
                    case "update" -> {
                        state.updates.add((UpdateWrapper<AgentWorkflowCheckpointEntity>) arguments[1]);
                        yield state.updateResults.isEmpty() ? 0 : state.updateResults.removeFirst();
                    }
                    case "toString" -> "AgentWorkflowCheckpointMapperTestProxy";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private AgentThreadMapper threadMapper(State state) {
        return (AgentThreadMapper) Proxy.newProxyInstance(
                AgentThreadMapper.class.getClassLoader(), new Class<?>[]{AgentThreadMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "selectForUpdate" -> {
                        state.calls.add("thread.lock");
                        yield state.thread;
                    }
                    case "setOpenInteraction" -> {
                        state.calls.add("thread.set");
                        state.thread.setOpenInteractionType((String) arguments[2]);
                        state.thread.setOpenInteractionId((String) arguments[3]);
                        yield 1;
                    }
                    case "clearOpenInteraction" -> {
                        state.calls.add("thread.clear");
                        state.pointerClears.incrementAndGet();
                        yield 1;
                    }
                    case "toString" -> "AgentThreadMapperTestProxy";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class State {
        private AgentWorkflowCheckpointEntity checkpoint;
        private AgentThreadEntity thread;
        private final Deque<Integer> updateResults = new ArrayDeque<>();
        private final List<UpdateWrapper<AgentWorkflowCheckpointEntity>> updates = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final AtomicInteger inserts = new AtomicInteger();
        private final AtomicInteger pointerClears = new AtomicInteger();
    }
}
