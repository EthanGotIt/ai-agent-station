package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型职责：验证 QuestionCard 回答 Turn 使用独立结构化列并可在重启后恢复。
 *
 * @author ethan
 * @date 2026-08-27
 */
class MybatisAgentTurnStoreQuestionAnswerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void persistsAndRestoresAgentQuestionAnswerInput() {
        AtomicReference<AgentTurnEntity> persisted = new AtomicReference<>();
        AgentTurnMapper turns = mapper(AgentTurnMapper.class, (method, arguments) -> switch (method) {
            case "insert" -> {
                persisted.set((AgentTurnEntity) arguments[0]);
                yield 1;
            }
            case "selectByRequest" -> persisted.get();
            default -> defaultValue(method);
        });
        AgentThreadEntity thread = new AgentThreadEntity();
        thread.setThreadId("thread-1");
        thread.setUserId("user-1");
        thread.setStatus("ACTIVE");
        thread.setNextSequence(1L);
        AgentItemMapper items = mapper(AgentItemMapper.class, (method, arguments) ->
                method.equals("insert") ? 1 : defaultValue(method));
        AgentThreadMapper threads = mapper(AgentThreadMapper.class, (method, arguments) -> switch (method) {
            case "selectForUpdate" -> thread;
            case "updateById" -> 1;
            default -> defaultValue(method);
        });
        MybatisAgentTurnStore store = new MybatisAgentTurnStore(
                turns, items, threads);
        AgentQuestionAnswerInput input = new AgentQuestionAnswerInput(
                "question-1", null, AgentQuestionCardResumeTargetEnum.AGENT, 2,
                Map.of("orderId", "ORDER-1"), AgentQuestionCardAnswerActionEnum.SUBMIT);
        AgentTurnModel turn = new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "QuestionCard 回答",
                AgentTurnStatusEnum.QUEUED, 1, null, null, NOW, null, null, input);
        AgentItemModel item = new AgentItemModel(
                "item-1", "thread-1", "turn-1", 0, AgentItemTypeEnum.QUESTION_ANSWER,
                "{\"questionId\":\"question-1\"}", NOW);

        assertEquals(1L, store.createTurnWithInitialItem(turn, item));
        AgentTurnModel restored = store.findTurnByRequest("user-1", "request-1").orElseThrow();
        assertEquals(input, restored.questionAnswerInput());
        assertEquals("QUESTION_ANSWER", persisted.get().getInputKind());
        assertEquals("question-1", persisted.get().getQuestionCardId());
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    private Object defaultValue(String method) {
        if (method.equals("toString")) return "MapperTestProxy";
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
