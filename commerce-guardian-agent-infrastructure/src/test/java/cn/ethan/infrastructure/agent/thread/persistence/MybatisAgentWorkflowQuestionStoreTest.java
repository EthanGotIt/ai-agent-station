package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * QuestionCard 持久化契约测试：验证父行互斥和回答 Turn 的负向 CAS 路径。
 *
 * @author ethan
 * @date 2026-08-20
 */
class MybatisAgentWorkflowQuestionStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void saveLocksParentBeforeInsertAndSetsOpenPointer() {
        State state = state(null, null, 1);
        MybatisAgentWorkflowQuestionStore store = store(state);

        store.saveQuestion(initialQuestion());

        assertEquals(List.of("thread.lock", "question.selectOpen", "question.insert", "thread.set"), state.calls);
        assertEquals(1, state.inserts.get());
        assertEquals(1, state.pointerSets.get());
    }

    @Test
    void occupiedParentPointerRejectsInsertEvenWithoutExistingQuestionRow() {
        State state = state(null, thread("question-existing"), 1);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertThrows(IllegalStateException.class, () -> store.saveQuestion(initialQuestion()));

        assertEquals(List.of("thread.lock"), state.calls);
        assertEquals(0, state.inserts.get());
    }

    @Test
    void saveRejectsNonInitialQuestionBeforeDatabaseWrite() {
        State state = state(null, null, 1);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertThrows(IllegalArgumentException.class,
                () -> store.saveQuestion(initialQuestion().reserveAnswerTurn("answer-turn-1")));

        assertTrue(state.calls.isEmpty());
        assertEquals(0, state.inserts.get());
    }

    @Test
    void reservedAnswerTurnCannotCloseQuestion() {
        State state = state(question("RESERVED", 1L, "answer-turn-1"), thread("question-1"), 0);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertFalse(store.closeAnswerTurn("user-1", "question-1", 1, "answer-turn-1", NOW));

        UpdateWrapper<AgentWorkflowQuestionEntity> wrapper = state.updates.get(0);
        wrapper.getSqlSegment();
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ENQUEUED"));
        assertFalse(wrapper.getParamNameValuePairs().containsValue("RESERVED"));
        assertEquals(0, state.pointerClears.get());
    }

    @Test
    void wrongTurnOrVersionCannotCloseQuestion() {
        State state = state(question("ENQUEUED", 2L, "answer-turn-1"), thread("question-1"), 0, 0);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertFalse(store.closeAnswerTurn("user-1", "question-1", 2, "answer-turn-other", NOW));
        assertFalse(store.closeAnswerTurn("user-1", "question-1", 1, "answer-turn-1", NOW));
        assertEquals(0, state.pointerClears.get());
    }

    @Test
    void releaseAdvancesVersionSoOldAnswerCannotClose() {
        State state = state(question("ENQUEUED", 2L, "answer-turn-1"), thread("question-1"), 1, 0);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertTrue(store.releaseAnswerTurn("user-1", "question-1", 2, "answer-turn-1"));
        assertFalse(store.closeAnswerTurn("user-1", "question-1", 2, "answer-turn-1", NOW));
        assertEquals(2, state.updates.size());
    }

    @Test
    void duplicateReservationFailsAfterFirstCas() {
        State state = state(question("AVAILABLE", 0L, null), thread("question-1"), 1, 0);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertEquals(1L, store.reserveAnswerTurn("user-1", "question-1", 0, "answer-turn-1").orElseThrow());
        assertTrue(store.reserveAnswerTurn("user-1", "question-1", 0, "answer-turn-2").isEmpty());
    }

    @Test
    void reservationRejectsQuestionWithoutMatchingThreadPointer() {
        State state = state(question("AVAILABLE", 0L, null), thread(null), 1);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertTrue(store.reserveAnswerTurn("user-1", "question-1", 0, "answer-turn-1").isEmpty());
        assertEquals(0, state.updates.size());
    }

    @Test
    void successfulCloseClearsMatchingParentPointer() {
        State state = state(question("ENQUEUED", 2L, "answer-turn-1"), thread("question-1"), 1);
        MybatisAgentWorkflowQuestionStore store = store(state);

        assertTrue(store.closeAnswerTurn("user-1", "question-1", 2, "answer-turn-1", NOW));
        assertEquals(1, state.pointerClears.get());
    }

    @Test
    void restoresStructuredAnswerSchemaFromPersistedFieldsJson() {
        AgentWorkflowQuestionEntity entity = question("AVAILABLE", 0L, null);
        entity.setFieldsJson("""
                {"fields":[{"name":"decision","required":true,"maxLength":16,
                "options":["APPROVE","REJECT"]}]}
                """);
        State state = state(entity, thread("question-1"), 1);
        MybatisAgentWorkflowQuestionStore store = store(state);

        AgentWorkflowQuestionModel restored = store.findOpenQuestion("user-1", "thread-1").orElseThrow();

        assertEquals(Map.of("decision", "APPROVE"),
                restored.validateAnswers(Map.of("decision", "APPROVE")));
        assertThrows(IllegalArgumentException.class,
                () -> restored.validateAnswers(Map.of("decision", "CONFIRM")));
    }

    private AgentWorkflowQuestionModel initialQuestion() {
        return new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "origin-turn-1", "user-1", "question-1", "checkpoint-1", 0,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null);
    }

    private State state(AgentWorkflowQuestionEntity question, AgentThreadEntity thread, int... updateResults) {
        State state = new State();
        state.question = question;
        state.thread = thread == null ? thread(null) : thread;
        for (int updateResult : updateResults) {
            state.updateResults.add(updateResult);
        }
        return state;
    }

    private AgentWorkflowQuestionEntity question(String enqueueStatus, long version, String answerTurnId) {
        AgentWorkflowQuestionEntity entity = new AgentWorkflowQuestionEntity();
        entity.setQuestionId("question-1");
        entity.setRunId("run-1");
        entity.setThreadId("thread-1");
        entity.setTurnId("origin-turn-1");
        entity.setUserId("user-1");
        entity.setCheckpointId("checkpoint-1");
        entity.setVersionNo(version);
        entity.setAnswerTurnId(answerTurnId);
        entity.setAnswerEnqueueStatus(enqueueStatus);
        entity.setStatus("OPEN");
        entity.setCreatedAt(NOW);
        return entity;
    }

    private AgentThreadEntity thread(String openQuestionId) {
        AgentThreadEntity entity = new AgentThreadEntity();
        entity.setThreadId("thread-1");
        entity.setUserId("user-1");
        entity.setOpenQuestionId(openQuestionId);
        return entity;
    }

    private MybatisAgentWorkflowQuestionStore store(State state) {
        return new MybatisAgentWorkflowQuestionStore(
                questionMapper(state), threadMapper(state), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private AgentWorkflowQuestionMapper questionMapper(State state) {
        return (AgentWorkflowQuestionMapper) Proxy.newProxyInstance(
                AgentWorkflowQuestionMapper.class.getClassLoader(),
                new Class<?>[]{AgentWorkflowQuestionMapper.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "selectById" -> state.question;
                        case "selectOpen" -> {
                            state.calls.add("question.selectOpen");
                            yield state.question;
                        }
                        case "insert" -> {
                            state.calls.add("question.insert");
                            state.inserts.incrementAndGet();
                            yield 1;
                        }
                        case "update" -> {
                            state.updates.add((UpdateWrapper<AgentWorkflowQuestionEntity>) arguments[1]);
                            yield state.updateResults.isEmpty() ? 0 : state.updateResults.removeFirst();
                        }
                        case "toString" -> "AgentWorkflowQuestionMapperTestProxy";
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private AgentThreadMapper threadMapper(State state) {
        return (AgentThreadMapper) Proxy.newProxyInstance(
                AgentThreadMapper.class.getClassLoader(),
                new Class<?>[]{AgentThreadMapper.class},
                (proxy, method, arguments) -> {
                    return switch (method.getName()) {
                        case "selectForUpdate" -> {
                            state.calls.add("thread.lock");
                            yield state.thread;
                        }
                        case "setOpenQuestion" -> {
                            state.calls.add("thread.set");
                            state.pointerSets.incrementAndGet();
                            yield 1;
                        }
                        case "clearOpenQuestion" -> {
                            state.calls.add("thread.clear");
                            state.pointerClears.incrementAndGet();
                            yield 1;
                        }
                        case "toString" -> "AgentThreadMapperTestProxy";
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    private static final class State {
        private AgentWorkflowQuestionEntity question;
        private AgentThreadEntity thread;
        private final Deque<Integer> updateResults = new ArrayDeque<>();
        private final List<UpdateWrapper<AgentWorkflowQuestionEntity>> updates = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final AtomicInteger inserts = new AtomicInteger();
        private final AtomicInteger pointerSets = new AtomicInteger();
        private final AtomicInteger pointerClears = new AtomicInteger();
    }
}
