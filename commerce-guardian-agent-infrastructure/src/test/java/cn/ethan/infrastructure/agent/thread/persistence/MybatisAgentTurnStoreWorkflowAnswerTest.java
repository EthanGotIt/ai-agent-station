package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 类型职责：验证回答 Turn 的明确列、answers JSON 和首 Item 在 MyBatis 边界完整往返。
 *
 * @author ethan
 * @date 2026-08-21
 */
class MybatisAgentTurnStoreWorkflowAnswerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void persistsAndRestoresStructuredWorkflowAnswerInput() throws Exception {
        AtomicReference<AgentTurnEntity> persistedTurn = new AtomicReference<>();
        AtomicReference<AgentItemEntity> persistedItem = new AtomicReference<>();
        AgentThreadEntity thread = new AgentThreadEntity();
        thread.setThreadId("thread-1");
        thread.setUserId("user-1");
        thread.setNextSequence(7L);
        ObjectMapper objectMapper = new ObjectMapper();
        JacksonAgentWorkflowAnswerCodec codec = new JacksonAgentWorkflowAnswerCodec(objectMapper);
        AgentTurnMapper turnMapper = mapper(AgentTurnMapper.class, (method, arguments) -> switch (method) {
            case "insert" -> {
                persistedTurn.set((AgentTurnEntity) arguments[0]);
                yield 1;
            }
            case "selectByRequest" -> persistedTurn.get();
            default -> defaultValue(method);
        });
        AgentItemMapper itemMapper = mapper(AgentItemMapper.class, (method, arguments) -> switch (method) {
            case "insert" -> {
                persistedItem.set((AgentItemEntity) arguments[0]);
                yield 1;
            }
            default -> defaultValue(method);
        });
        AgentThreadMapper threadMapper = mapper(AgentThreadMapper.class, (method, arguments) -> switch (method) {
            case "selectForUpdate" -> thread;
            case "updateById" -> 1;
            default -> defaultValue(method);
        });
        MybatisAgentTurnStore store = new MybatisAgentTurnStore(
                turnMapper, itemMapper, threadMapper, codec);
        AgentWorkflowAnswerInput answerInput = new AgentWorkflowAnswerInput(
                "run-1", "question-1", "checkpoint-1", 2,
                Map.of("decision", "APPROVE", "reason", "可恢复"));
        AgentTurnModel turn = new AgentTurnModel(
                "answer-turn-1", "thread-1", "user-1", "request-1", "QuestionCard 回答",
                AgentTurnStatusEnum.QUEUED, 1, "run-1", null, NOW, null, null, answerInput);
        AgentItemModel item = new AgentItemModel(
                "item-1", "thread-1", "answer-turn-1", 0,
                AgentItemTypeEnum.WORKFLOW_ANSWER, codec.encodeItem(answerInput), NOW);

        long sequence = store.createTurnWithInitialItem(turn, item);
        AgentTurnModel restored = store.findTurnByRequest("user-1", "request-1").orElseThrow();

        assertEquals(7L, sequence);
        assertEquals("question-1", persistedTurn.get().getWorkflowQuestionId());
        assertEquals("checkpoint-1", persistedTurn.get().getWorkflowCheckpointId());
        assertEquals(2L, persistedTurn.get().getWorkflowQuestionVersion());
        assertEquals(0L, persistedTurn.get().getVersionNo());
        assertEquals("APPROVE", objectMapper.readTree(persistedTurn.get().getWorkflowAnswersJson())
                .path("decision").asString());
        assertEquals("可恢复", objectMapper.readTree(persistedTurn.get().getWorkflowAnswersJson())
                .path("reason").asString());
        assertNotNull(persistedItem.get());
        assertEquals(7L, persistedItem.get().getSequenceNo());
        assertEquals(answerInput, restored.workflowAnswerInput());
        assertEquals(0L, restored.version());

        persistedTurn.get().setWorkflowAnswersJson(null);
        AgentTurnModel restoredOwner = store.findTurnByRequest("user-1", "request-1").orElseThrow();
        assertNull(restoredOwner.workflowAnswerInput());
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    private static Object defaultValue(String method) {
        if (method.equals("toString")) return "MapperTestProxy";
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
