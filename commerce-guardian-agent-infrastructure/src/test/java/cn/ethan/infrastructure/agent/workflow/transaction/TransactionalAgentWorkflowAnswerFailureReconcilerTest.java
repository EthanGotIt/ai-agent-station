package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentWorkflowAnswerFailureReconciler;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 类型职责：验证失败回答的 Question release 与 Turn 终态在同一本地事务提交或回滚。
 *
 * @author ethan
 * @date 2026-08-21
 */
class TransactionalAgentWorkflowAnswerFailureReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void releasesQuestionAndFailsTurnInOneTransaction() {
        Fixture fixture = new Fixture();

        assertTrue(fixture.reconciler.reconcile(
                fixture.turns.turn, AgentTurnStatusEnum.FAILED, "ENGINE_DB_FAILED", NOW));

        assertEquals(3L, fixture.questions.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.questions.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.FAILED, fixture.turns.turn.status());
        assertEquals(List.of("question.release", "turn.update"), fixture.calls);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);
    }

    @Test
    void commitsCurrentQuestionVersionAsRetryItem() {
        java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        QuestionStore questions = new QuestionStore(calls);
        TurnStore turns = new TurnStore(calls);
        ItemStore items = new ItemStore();
        SnapshotTransactionManager transactions = new SnapshotTransactionManager(questions, turns, items);
        TransactionalAgentWorkflowAnswerFailureReconciler reconciler =
                new TransactionalAgentWorkflowAnswerFailureReconciler(questions, turns, items, transactions);

        AgentWorkflowAnswerFailureReconciler.ReconciliationResult result = reconciler.reconcileWithProjection(
                turns.turn, AgentTurnStatusEnum.FAILED, "ENGINE_DB_FAILED", NOW);

        assertTrue(result.reconciled());
        assertNotNull(result.retryQuestionItem());
        assertEquals(AgentItemTypeEnum.WORKFLOW_QUESTION, result.retryQuestionItem().type());
        assertTrue(result.retryQuestionItem().payload().contains("\"version\":3"));
        assertEquals(1, items.values.size());
        assertEquals(1L, result.retryQuestionItem().sequence());
    }

    @Test
    void rollsBackQuestionReleaseWhenTurnTerminalWriteFails() {
        Fixture fixture = new Fixture();
        fixture.turns.failUpdate = true;

        assertThrows(IllegalStateException.class, () -> fixture.reconciler.reconcile(
                fixture.turns.turn, AgentTurnStatusEnum.FAILED, "ENGINE_DB_FAILED", NOW));

        assertEquals(2L, fixture.questions.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                fixture.questions.question.answerEnqueueStatus());
        assertEquals("answer-turn-1", fixture.questions.question.answerTurnId());
        assertTrue(fixture.items.values.isEmpty());
        assertEquals(AgentTurnStatusEnum.ACTIVE, fixture.turns.turn.status());
        assertEquals(0, fixture.transactions.commits);
        assertEquals(1, fixture.transactions.rollbacks);
    }

    @Test
    void rollsBackQuestionReleaseWhenTurnCasLosesRace() {
        Fixture fixture = new Fixture();
        fixture.turns.rejectUpdate = true;

        assertThrows(AgentThreadConflictException.class, () -> fixture.reconciler.reconcile(
                fixture.turns.turn, AgentTurnStatusEnum.FAILED, "ENGINE_DB_FAILED", NOW));

        assertEquals(2L, fixture.questions.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                fixture.questions.question.answerEnqueueStatus());
        assertTrue(fixture.items.values.isEmpty());
        assertEquals(AgentTurnStatusEnum.ACTIVE, fixture.turns.turn.status());
        assertEquals(0, fixture.transactions.commits);
        assertEquals(1, fixture.transactions.rollbacks);
    }

    @Test
    void releasesQuestionWithoutRewritingAnAlreadyTerminalTurn() {
        Fixture fixture = new Fixture();
        fixture.turns.turn = fixture.turns.turn.terminal(AgentTurnStatusEnum.FAILED, "ALREADY_FAILED", NOW);

        assertTrue(fixture.reconciler.reconcile(
                fixture.turns.turn, AgentTurnStatusEnum.FAILED, "RETRY", NOW));

        assertEquals(List.of("question.release"), fixture.calls);
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.questions.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.FAILED, fixture.turns.turn.status());
        assertEquals(1, fixture.transactions.commits);
    }

    @Test
    void answeredQuestionIsNeverReopenedDuringFailureConvergence() {
        Fixture fixture = new Fixture();
        fixture.questions.question = new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "origin-turn-1", "user-1", "question-1", "checkpoint-1", 3,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.ANSWERED, NOW, NOW,
                "answer-turn-1", AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED);

        assertTrue(fixture.reconciler.reconcile(
                fixture.turns.turn, AgentTurnStatusEnum.FAILED, "POST_CLOSE_FAILURE", NOW));

        assertEquals(AgentWorkflowQuestionStatusEnum.ANSWERED, fixture.questions.question.status());
        assertEquals(3L, fixture.questions.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED,
                fixture.questions.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.FAILED, fixture.turns.turn.status());
    }

    private static final class Fixture {
        private final java.util.ArrayList<String> calls = new java.util.ArrayList<>();
        private final QuestionStore questions = new QuestionStore(calls);
        private final TurnStore turns = new TurnStore(calls);
        private final ItemStore items = new ItemStore();
        private final SnapshotTransactionManager transactions =
                new SnapshotTransactionManager(questions, turns, items);
        private final TransactionalAgentWorkflowAnswerFailureReconciler reconciler =
                new TransactionalAgentWorkflowAnswerFailureReconciler(questions, turns, items, transactions);
    }

    private static final class QuestionStore implements AgentWorkflowQuestionStore {
        private final List<String> calls;
        private AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "origin-turn-1", "user-1", "question-1", "checkpoint-1", 2,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null,
                "answer-turn-1", AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED);

        private QuestionStore(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
            return Optional.of(question);
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
            return Optional.ofNullable(question)
                    .filter(value -> value.status() == AgentWorkflowQuestionStatusEnum.OPEN);
        }

        @Override
        public void saveQuestion(AgentWorkflowQuestionModel value) {
            question = value;
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
            calls.add("question.release");
            if (question.status() != AgentWorkflowQuestionStatusEnum.OPEN
                    || question.version() != expectedVersion || !answerTurnId.equals(question.answerTurnId())) {
                return false;
            }
            question = question.releaseAnswerTurn();
            return true;
        }

        @Override
        public boolean closeAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId, Instant answeredAt
        ) {
            return false;
        }
    }

    private static final class TurnStore implements AgentTurnStore {
        private final List<String> calls;
        private AgentTurnModel turn = new AgentTurnModel(
                "answer-turn-1", "thread-1", "user-1", "request-1", "QuestionCard 回答",
                AgentTurnStatusEnum.ACTIVE, 1, "run-1", null, NOW, NOW, null,
                new AgentWorkflowAnswerInput(
                        "run-1", "question-1", "checkpoint-1", 2, Map.of("decision", "APPROVE")));
        private boolean failUpdate;
        private boolean rejectUpdate;

        private TurnStore(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            return Optional.of(turn);
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return Optional.of(turn);
        }

        @Override
        public void createTurn(AgentTurnModel value) {
            turn = value;
        }

        @Override
        public long createTurnWithInitialItem(AgentTurnModel value, AgentItemModel initialItem) {
            turn = value;
            return 1;
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel value) {
            calls.add("turn.update");
            if (failUpdate) throw new IllegalStateException("模拟 Turn 终态写入失败");
            if (rejectUpdate) return false;
            turn = value;
            return true;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return List.of(turn);
        }
    }

    private static final class ItemStore implements AgentItemStore {
        private final java.util.ArrayList<AgentItemModel> values = new java.util.ArrayList<>();

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

    private static final class SnapshotTransactionManager
            implements org.springframework.transaction.PlatformTransactionManager {
        private final QuestionStore questions;
        private final TurnStore turns;
        private final ItemStore items;
        private AgentWorkflowQuestionModel questionSnapshot;
        private AgentTurnModel turnSnapshot;
        private List<AgentItemModel> itemSnapshot;
        private int commits;
        private int rollbacks;

        private SnapshotTransactionManager(QuestionStore questions, TurnStore turns, ItemStore items) {
            this.questions = questions;
            this.turns = turns;
            this.items = items;
        }

        @Override
        public @NonNull TransactionStatus getTransaction(TransactionDefinition definition) {
            questionSnapshot = questions.question;
            turnSnapshot = turns.turn;
            itemSnapshot = List.copyOf(items.values);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(@NonNull TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(@NonNull TransactionStatus status) {
            rollbacks++;
            questions.question = questionSnapshot;
            turns.turn = turnSnapshot;
            items.values.clear();
            items.values.addAll(itemSnapshot);
        }
    }
}
