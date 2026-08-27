package cn.ethan.infrastructure.agent.workflow.persistence;

import cn.ethan.core.agent.thread.AgentInteractionTypeEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerEnqueueStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立 QuestionCard 持久化测试：验证 Thread 交互互斥和回答 CAS 生命周期。
 *
 * @author ethan
 * @date 2026-08-27
 */
class MybatisAgentQuestionCardStoreTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void createLocksThreadBeforeInsertAndSetsGenericPointer() {
        State state = state(null, thread(null, null), 1);
        MybatisAgentQuestionCardStore store = store(state);

        store.create(question());

        assertEquals(List.of("thread.lock", "question.selectOpen", "question.insert", "thread.set"), state.calls);
        assertEquals(1, state.inserts.get());
        assertEquals(1, state.pointerSets.get());
        assertEquals(AgentInteractionTypeEnum.QUESTION_CARD.name(), state.thread.getOpenInteractionType());
    }

    @Test
    void createRejectsCheckpointPointerEvenWhenQuestionTableIsEmpty() {
        State state = state(null, thread("checkpoint-1", AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name()), 1);
        MybatisAgentQuestionCardStore store = store(state);

        assertThrows(IllegalStateException.class, () -> store.create(question()));
        assertEquals(0, state.inserts.get());
        assertEquals(List.of("thread.lock"), state.calls);
    }

    @Test
    void reservationAndEnqueueUseVersionAndTurnCas() {
        State state = state(questionEntity("AVAILABLE", 0L, null),
                thread("question-1", AgentInteractionTypeEnum.QUESTION_CARD.name()), 1, 1);
        MybatisAgentQuestionCardStore store = store(state);

        assertEquals(1L, store.reserveAnswerTurn("user-1", "question-1", 0, "answer-1").orElseThrow());
        assertEquals(2L, store.markAnswerTurnEnqueued("user-1", "question-1", 1, "answer-1").orElseThrow());
        assertEquals(2, state.updates.size());
        assertTrue(state.updates.get(0).getParamNameValuePairs().containsValue("RESERVED"));
        assertTrue(state.updates.get(1).getParamNameValuePairs().containsValue("ENQUEUED"));
    }

    @Test
    void duplicateReservationAndWrongPointerAreRejected() {
        State state = state(questionEntity("AVAILABLE", 0L, null), thread(null, null));
        MybatisAgentQuestionCardStore store = store(state);

        assertTrue(store.reserveAnswerTurn("user-1", "question-1", 0, "answer-1").isEmpty());
        assertEquals(0, state.updates.size());

        state.thread.setOpenInteractionId("question-1");
        state.thread.setOpenInteractionType(AgentInteractionTypeEnum.QUESTION_CARD.name());
        state.updateResults.add(1);
        assertEquals(1L, store.reserveAnswerTurn("user-1", "question-1", 0, "answer-1").orElseThrow());
        assertTrue(store.reserveAnswerTurn("user-1", "question-1", 0, "answer-2").isEmpty());
    }

    @Test
    void closeRequiresEnqueuedTurnAndClearsPointer() {
        State state = state(questionEntity("ENQUEUED", 2L, "answer-1"),
                thread("question-1", AgentInteractionTypeEnum.QUESTION_CARD.name()), 0, 1);
        MybatisAgentQuestionCardStore store = store(state);

        assertFalse(store.closeAnswerTurn("user-1", "question-1", 1, "answer-1",
                AgentQuestionCardStatusEnum.ANSWERED, NOW));
        assertTrue(store.closeAnswerTurn("user-1", "question-1", 2, "answer-1",
                AgentQuestionCardStatusEnum.ANSWERED, NOW));
        assertEquals(1, state.pointerClears.get());
        assertTrue(state.updates.get(1).getParamNameValuePairs().containsValue("CONSUMED"));
    }

    @Test
    void restoredFieldsJsonKeepsQuestionValidationSchema() {
        AgentQuestionCardEntity entity = questionEntity("AVAILABLE", 0L, null);
        entity.setFieldsJson("{\"fields\":[{\"name\":\"orderId\",\"required\":true,"
                + "\"maxLength\":64}]}");
        State state = state(entity, thread("question-1", AgentInteractionTypeEnum.QUESTION_CARD.name()), 1);
        MybatisAgentQuestionCardStore store = store(state);

        assertEquals("ORDER-1", store.findOpen("user-1", "thread-1").orElseThrow()
                .validateAnswers(java.util.Map.of("orderId", "ORDER-1")).get("orderId"));
    }

    private AgentQuestionCardModel question() {
        return AgentQuestionCardModel.agent("question-1", "thread-1", "origin-turn-1", "user-1",
                "补充信息", "请补充订单号", "[]",
                List.of(new AgentWorkflowQuestionFieldModel("orderId", true, 64, List.of())), NOW);
    }

    private State state(AgentQuestionCardEntity question, AgentThreadEntity thread, int... results) {
        State state = new State();
        state.question = question;
        state.thread = thread;
        for (int result : results) {
            state.updateResults.add(result);
        }
        return state;
    }

    private AgentQuestionCardEntity questionEntity(String enqueueStatus, long version, String answerTurnId) {
        AgentQuestionCardEntity entity = new AgentQuestionCardEntity();
        entity.setQuestionId("question-1");
        entity.setThreadId("thread-1");
        entity.setTurnId("origin-turn-1");
        entity.setUserId("user-1");
        entity.setResumeTarget("AGENT");
        entity.setVersionNo(version);
        entity.setAnswerTurnId(answerTurnId);
        entity.setAnswerEnqueueStatus(enqueueStatus);
        entity.setStatus("OPEN");
        entity.setTitle("补充信息");
        entity.setPrompt("请补充");
        entity.setFieldsJson("[]");
        entity.setCreatedAt(NOW);
        return entity;
    }

    private AgentThreadEntity thread(String openInteractionId, String openInteractionType) {
        AgentThreadEntity entity = new AgentThreadEntity();
        entity.setThreadId("thread-1");
        entity.setUserId("user-1");
        entity.setOpenInteractionId(openInteractionId);
        entity.setOpenInteractionType(openInteractionType);
        entity.setOpenQuestionId(openInteractionType != null
                && AgentInteractionTypeEnum.QUESTION_CARD.name().equals(openInteractionType)
                ? openInteractionId : null);
        return entity;
    }

    private MybatisAgentQuestionCardStore store(State state) {
        return new MybatisAgentQuestionCardStore(questionMapper(state), threadMapper(state), new ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private AgentQuestionCardMapper questionMapper(State state) {
        return (AgentQuestionCardMapper) Proxy.newProxyInstance(
                AgentQuestionCardMapper.class.getClassLoader(), new Class<?>[]{AgentQuestionCardMapper.class},
                (proxy, method, arguments) -> switch (method.getName()) {
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
                        state.updates.add((UpdateWrapper<AgentQuestionCardEntity>) arguments[1]);
                        yield state.updateResults.isEmpty() ? 0 : state.updateResults.removeFirst();
                    }
                    case "toString" -> "AgentQuestionCardMapperTestProxy";
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
                        state.pointerSets.incrementAndGet();
                        state.thread.setOpenInteractionType((String) arguments[2]);
                        state.thread.setOpenInteractionId((String) arguments[3]);
                        if (AgentInteractionTypeEnum.QUESTION_CARD.name().equals(arguments[2])) {
                            state.thread.setOpenQuestionId((String) arguments[3]);
                        }
                        yield 1;
                    }
                    case "clearOpenInteraction" -> {
                        state.calls.add("thread.clear");
                        state.pointerClears.incrementAndGet();
                        state.thread.setOpenInteractionId(null);
                        state.thread.setOpenInteractionType(null);
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
        private AgentQuestionCardEntity question;
        private AgentThreadEntity thread;
        private final Deque<Integer> updateResults = new ArrayDeque<>();
        private final List<UpdateWrapper<AgentQuestionCardEntity>> updates = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final AtomicInteger inserts = new AtomicInteger();
        private final AtomicInteger pointerSets = new AtomicInteger();
        private final AtomicInteger pointerClears = new AtomicInteger();
    }
}
