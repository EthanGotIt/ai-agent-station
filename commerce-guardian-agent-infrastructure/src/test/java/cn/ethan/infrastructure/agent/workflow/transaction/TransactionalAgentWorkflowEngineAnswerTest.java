package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.action.ExternalActionCommandStore;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowEngine;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.commerce.order.OrderLookupResultModel;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 类型职责：验证 Workflow Engine 只消费持久化回答，并以 ENQUEUED 真实版本关闭 QuestionCard。
 *
 * @author ethan
 * @date 2026-08-21
 */
class TransactionalAgentWorkflowEngineAnswerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void closesWithPersistedEnqueuedVersionAndIgnoresTransientAnswers() {
        QuestionStore questions = new QuestionStore();
        RunStore runs = new RunStore();
        ItemStore items = new ItemStore();
        ExternalActionCommandStore commands = (ExternalActionCommandStore) Proxy.newProxyInstance(
                ExternalActionCommandStore.class.getClassLoader(),
                new Class<?>[]{ExternalActionCommandStore.class},
                (proxy, method, arguments) -> defaultValue(method.getReturnType()));
        TransactionalAgentWorkflowEngine engine = new TransactionalAgentWorkflowEngine(
                Clock.fixed(NOW, ZoneOffset.UTC), questions, commands, new ObjectMapper(), runs,
                (orderId, userId) -> OrderLookupResultModel.notFound(), items);
        AgentThreadModel thread = new AgentThreadModel(
                "thread-1", "user-1", "测试", AgentThreadStatusEnum.ACTIVE,
                null, null, 0, NOW, NOW);
        AgentWorkflowAnswerInput persistedInput = new AgentWorkflowAnswerInput(
                "run-1", "question-1", "checkpoint-1", 2, Map.of("decision", "REJECT"));
        AgentTurnModel answerTurn = new AgentTurnModel(
                "answer-turn-1", "thread-1", "user-1", "request-1", "QuestionCard 回答",
                AgentTurnStatusEnum.ACTIVE, 1, "run-1", null, NOW, NOW, null, persistedInput);

        AgentWorkflowEngine.ResumeResult result = engine.resume(
                thread, answerTurn, Map.of("decision", "APPROVE"));

        assertEquals("REJECTED", result.resultStatus());
        assertEquals(2L, questions.closedExpectedVersion);
        assertEquals("answer-turn-1", questions.closedAnswerTurnId);
        assertEquals(AgentWorkflowStatusEnum.REJECTED, runs.updated.status());
        assertEquals(1, items.values.size());
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == List.class) return List.of();
        return null;
    }

    private static final class QuestionStore implements AgentWorkflowQuestionStore {
        private final AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "origin-turn-1", "user-1", "question-1", "checkpoint-1", 2,
                "确认", "请确认", "{}", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null,
                "answer-turn-1", AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                List.of(new AgentWorkflowQuestionFieldModel(
                        "decision", true, 32, List.of("APPROVE", "REJECT"))));
        private long closedExpectedVersion = -1;
        private String closedAnswerTurnId;

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
            return Optional.of(question);
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
            return Optional.of(question);
        }

        @Override
        public void saveQuestion(AgentWorkflowQuestionModel value) {
        }

        @Override
        public OptionalLong reserveAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            return OptionalLong.empty();
        }

        @Override
        public OptionalLong markAnswerTurnEnqueued(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            return OptionalLong.empty();
        }

        @Override
        public boolean releaseAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            return false;
        }

        @Override
        public boolean closeAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId, Instant answeredAt
        ) {
            closedExpectedVersion = expectedVersion;
            closedAnswerTurnId = answerTurnId;
            return expectedVersion == 2 && question.answerTurnId().equals(answerTurnId);
        }
    }

    private static final class RunStore implements AgentWorkflowRunStore {
        private final AgentWorkflowRunModel run = new AgentWorkflowRunModel(
                "run-1", "thread-1", "origin-turn-1", "user-1", AgentWorkflowTypeEnum.REFUND,
                AgentWorkflowStatusEnum.WAITING_USER_INPUT, 0, NOW, NOW);
        private AgentWorkflowRunModel updated;

        @Override
        public void create(AgentWorkflowRunModel value) {
        }

        @Override
        public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
            return Optional.of(run);
        }

        @Override
        public void update(AgentWorkflowRunModel value) {
            updated = value;
        }
    }

    private static final class ItemStore implements AgentItemStore {
        private final List<AgentItemModel> values = new ArrayList<>();

        @Override
        public long appendItem(AgentItemModel item) {
            values.add(item);
            return values.size();
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return List.copyOf(values);
        }
    }
}
