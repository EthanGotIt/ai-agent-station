package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentQuestionAnswerInput;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerActionEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 类型职责：验证 Turn Store 将结构化回答、版本条件、单调推进和终态保护落实到 MyBatis 边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class MybatisAgentTurnStoreVersionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void casUsesExpectedVersionAndReturnsFalseWhenNoRowWasUpdated() {
        AtomicInteger updateResult = new AtomicInteger(1);
        AtomicReference<UpdateWrapper<AgentTurnEntity>> captured = new AtomicReference<>();
        AgentTurnMapper mapper = mapper(AgentTurnMapper.class, (method, arguments) -> {
            if (method.equals("update")) {
                captured.set((UpdateWrapper<AgentTurnEntity>) arguments[1]);
                return updateResult.get();
            }
            return defaultValue(method);
        });
        MybatisAgentTurnStore store = store(mapper);
        AgentTurnModel expected = turn();
        AgentTurnModel next = expected.active(NOW.plusSeconds(1));

        assertTrue(store.updateTurn(expected, next));
        UpdateWrapper<AgentTurnEntity> wrapper = captured.get();
        assertNotNull(wrapper);
        assertTrue(wrapper.getSqlSet().contains("VERSION_NO"));
        assertTrue(wrapper.getExpression().getSqlSegment().contains("VERSION_NO"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue(1L));

        updateResult.set(0);
        assertFalse(store.updateTurn(expected, next));
    }

    @Test
    void terminalTurnCannotBeRewrittenEvenWithTheCurrentVersion() {
        AtomicInteger updates = new AtomicInteger();
        AgentTurnMapper mapper = mapper(AgentTurnMapper.class, (method, arguments) -> {
            if (method.equals("update")) {
                updates.incrementAndGet();
                return 1;
            }
            return defaultValue(method);
        });
        MybatisAgentTurnStore store = store(mapper);
        AgentTurnModel terminal = turn().terminal(AgentTurnStatusEnum.COMPLETED, null, NOW.plusSeconds(1));
        AgentTurnModel illegalNext = terminal.terminal(AgentTurnStatusEnum.FAILED, "LATE", NOW.plusSeconds(2));

        assertFalse(store.updateTurn(terminal, illegalNext));
        assertEquals(0, updates.get());
    }

    @Test
    void workflowOwnerLookupUsesUserAndRunIdentity() {
        AtomicReference<Object[]> arguments = new AtomicReference<>();
        AgentTurnMapper mapper = mapper(AgentTurnMapper.class, (method, values) -> {
            if (method.equals("selectWorkflowOwnerByTaskId")) {
                arguments.set(values);
                AgentTurnEntity entity = new AgentTurnEntity();
                entity.setTurnId("owner-turn");
                entity.setThreadId("thread-1");
                entity.setUserId("user-1");
                entity.setClientRequestId("owner-request");
                entity.setInputText("message");
                entity.setStatus(AgentTurnStatusEnum.COMPLETED.name());
                entity.setInputKind("MESSAGE");
                entity.setCreatedAt(NOW);
                entity.setVersionNo(1L);
                return entity;
            }
            return defaultValue(method);
        });

        AgentTurnModel owner = store(mapper)
                .findWorkflowOwnerTurnByTaskId("user-1", "run-1")
                .orElseThrow();

        assertEquals("user-1", arguments.get()[0]);
        assertEquals("run-1", arguments.get()[1]);
        assertEquals("owner-turn", owner.turnId());
        assertEquals("thread-1", owner.threadId());
    }

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
        MybatisAgentTurnStore store = new MybatisAgentTurnStore(turns, items, threads);
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

    private MybatisAgentTurnStore store(AgentTurnMapper mapper) {
        return new MybatisAgentTurnStore(
                mapper,
                mapper(AgentItemMapper.class, (method, arguments) -> defaultValue(method)),
                mapper(AgentThreadMapper.class, (method, arguments) -> defaultValue(method)));
    }

    private AgentTurnModel turn() {
        return new AgentTurnModel(
                "turn-1", "thread-1", "user-1", "request-1", "message",
                AgentTurnStatusEnum.QUEUED, 0, null, null, NOW, null, null);
    }

    @SuppressWarnings("unchecked")
    private <T> T mapper(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method.getName(), arguments));
    }

    private static Object defaultValue(String method) {
        if (method.equals("toString")) {
            return "MapperTestProxy";
        }
        if (method.equals("selectList")) {
            return java.util.List.of();
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
