package cn.ethan.infrastructure.agent.workflow.transaction;

import cn.ethan.core.agent.execution.AgentWorkflowAnswerAdmissionCommand;
import cn.ethan.core.agent.execution.AgentWorkflowAnswerAdmissionResult;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.infrastructure.agent.thread.persistence.JacksonAgentWorkflowAnswerCodec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类型职责：验证 Workflow 回答 admission 的事务顺序、幂等和失败回滚边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class TransactionalAgentWorkflowAnswerAdmissionTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void admitsAnswerInOneTransactionAndReturnsPersistedEnqueuedVersion() throws Exception {
        Fixture fixture = new Fixture();

        AgentWorkflowAnswerAdmissionResult result = fixture.admission.admit(command(Map.of(
                "decision", "APPROVE", "note", "同意退款")));

        assertTrue(result.newlyAdmitted());
        assertEquals(2L, result.enqueuedQuestionVersion());
        assertEquals(2L, result.turn().workflowAnswerInput().enqueuedQuestionVersion());
        assertEquals(11L, result.initialItem().sequence());
        assertEquals(List.of("turn.find", "question.find", "question.reserve",
                "turn.createWithItem", "question.mark"), fixture.calls);
        assertEquals(1, fixture.transactions.commits);
        assertEquals(0, fixture.transactions.rollbacks);

        JsonNode item = fixture.objectMapper.readTree(result.initialItem().payload());
        assertEquals(1, item.path("schemaVersion").asInt());
        assertEquals("WORKFLOW_ANSWER", item.path("kind").asString());
        assertEquals("run-1", item.path("data").path("runId").asString());
        assertEquals("question-1", item.path("data").path("questionId").asString());
        assertEquals("checkpoint-1", item.path("data").path("checkpointId").asString());
        assertEquals(2L, item.path("data").path("enqueuedQuestionVersion").asLong());
        assertEquals("同意退款", item.path("data").path("answers").path("note").asString());
    }

    @Test
    void duplicateClientRequestReturnsExistingTurnWithoutSecondReservation() {
        Fixture fixture = new Fixture();
        AgentWorkflowAnswerAdmissionResult first = fixture.admission.admit(command(Map.of("decision", "APPROVE")));
        fixture.calls.clear();

        AgentWorkflowAnswerAdmissionResult duplicate = fixture.admission.admit(
                command(Map.of("decision", "APPROVE")));

        assertFalse(duplicate.newlyAdmitted());
        assertEquals(first.turn().turnId(), duplicate.turn().turnId());
        assertEquals(2L, duplicate.enqueuedQuestionVersion());
        assertEquals(List.of("turn.find"), fixture.calls);
        assertEquals(1, fixture.questions.reserveCalls);
        assertEquals(1, fixture.questions.markCalls);
    }

    @Test
    void duplicateClientRequestWithDifferentContentReturnsStableConflict() {
        Fixture fixture = new Fixture();
        fixture.admission.admit(command(Map.of("decision", "APPROVE")));

        AgentThreadConflictException conflict = assertThrows(AgentThreadConflictException.class,
                () -> fixture.admission.admit(command(Map.of("decision", "REJECT"))));

        assertEquals("CLIENT_REQUEST_CONFLICT", conflict.code());
        assertEquals(1, fixture.questions.reserveCalls);
    }

    @Test
    void concurrentDuplicateRequestReadsWinnerAfterQuestionCasConflict() {
        Fixture winner = new Fixture();
        AgentWorkflowAnswerAdmissionResult admitted = winner.admission.admit(
                command(Map.of("decision", "APPROVE")));

        Fixture raced = new Fixture();
        raced.questions.failReserve = true;
        raced.turns.hideInitialRequest = true;
        raced.turns.lockedDuplicate = admitted.turn();

        AgentWorkflowAnswerAdmissionResult duplicate = raced.admission.admit(
                command(Map.of("decision", "APPROVE")));

        assertFalse(duplicate.newlyAdmitted());
        assertEquals(admitted.turn().turnId(), duplicate.turn().turnId());
        assertEquals(List.of("turn.find", "question.find", "question.reserve", "turn.find.lock"),
                raced.calls);
        assertEquals(0, raced.questions.markCalls);
    }

    @Test
    void markFailureRollsBackTransactionAfterAtomicTurnWrite() {
        Fixture fixture = new Fixture();
        fixture.questions.failMark = true;

        AgentThreadConflictException conflict = assertThrows(AgentThreadConflictException.class,
                () -> fixture.admission.admit(command(Map.of("decision", "APPROVE"))));

        assertEquals("WORKFLOW_VERSION_CONFLICT", conflict.code());
        assertEquals(List.of("turn.find", "question.find", "question.reserve",
                "turn.createWithItem", "question.mark"), fixture.calls);
        assertEquals(0, fixture.transactions.commits);
        assertEquals(1, fixture.transactions.rollbacks);
        assertEquals(0L, fixture.questions.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.questions.question.answerEnqueueStatus());
        assertNull(fixture.questions.question.answerTurnId());
        assertNull(fixture.turns.turn);
        assertNull(fixture.turns.initialItem);
        assertEquals(11L, fixture.turns.nextSequence);
    }

    @Test
    void rejectsAnswersOutsidePersistedQuestionSchemaBeforeReservation() {
        assertSchemaRejected(Map.of("decision", "APPROVE", "apiKey", "secret"));
        assertSchemaRejected(Map.of("note", "missing decision"));
        assertSchemaRejected(Map.of("decision", "CONFIRM"));
        assertSchemaRejected(Map.of("decision", " "));
        assertSchemaRejected(Map.of("decision", "APPROVE", "note", "x".repeat(129)));
    }

    private void assertSchemaRejected(Map<String, String> answers) {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.admission.admit(command(answers)));

        assertEquals(0L, fixture.questions.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.questions.question.answerEnqueueStatus());
        assertEquals(0, fixture.questions.reserveCalls);
        assertTrue(fixture.turns.findTurnByRequest("user-1", "request-1").isEmpty());
    }

    private AgentWorkflowAnswerAdmissionCommand command(Map<String, String> answers) {
        return new AgentWorkflowAnswerAdmissionCommand(
                "user-1", "thread-1", "request-1", 1, "run-1",
                "question-1", "checkpoint-1", 0, answers);
    }

    private static final class Fixture {
        private final List<String> calls = new ArrayList<>();
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final QuestionStore questions = new QuestionStore(calls);
        private final TurnStore turns = new TurnStore(calls);
        private final RecordingTransactionManager transactions =
                new RecordingTransactionManager(questions, turns);
        private final TransactionalAgentWorkflowAnswerAdmission admission =
                new TransactionalAgentWorkflowAnswerAdmission(
                        questions, turns, new JacksonAgentWorkflowAnswerCodec(objectMapper),
                        Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    }

    private static final class QuestionStore implements AgentWorkflowQuestionStore {
        private final List<String> calls;
        private AgentWorkflowQuestionModel question = new AgentWorkflowQuestionModel(
                "run-1", "thread-1", "origin-turn-1", "user-1", "question-1", "checkpoint-1", 0,
                "确认", "请确认", "[]", AgentWorkflowQuestionStatusEnum.OPEN, NOW, null,
                null, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                List.of(
                        new AgentWorkflowQuestionFieldModel(
                                "decision", true, 32, List.of("APPROVE", "REJECT")),
                        new AgentWorkflowQuestionFieldModel("note", false, 128, List.of())));
        private boolean failMark;
        private boolean failReserve;
        private int reserveCalls;
        private int markCalls;

        private QuestionStore(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
            calls.add("question.find");
            return Optional.of(question);
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
            return Optional.of(question);
        }

        @Override
        public void saveQuestion(AgentWorkflowQuestionModel next) {
            question = next;
        }

        @Override
        public OptionalLong reserveAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            calls.add("question.reserve");
            reserveCalls++;
            if (failReserve || question.version() != expectedVersion
                    || question.answerEnqueueStatus()
                    != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE) {
                return OptionalLong.empty();
            }
            question = question.reserveAnswerTurn(answerTurnId);
            return OptionalLong.of(question.version());
        }

        @Override
        public OptionalLong markAnswerTurnEnqueued(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            calls.add("question.mark");
            markCalls++;
            if (failMark || question.version() != expectedVersion
                    || !answerTurnId.equals(question.answerTurnId())) {
                return OptionalLong.empty();
            }
            question = question.answerTurnEnqueued();
            return OptionalLong.of(question.version());
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
            return false;
        }
    }

    private static final class TurnStore implements AgentTurnStore {
        private final List<String> calls;
        private AgentTurnModel turn;
        private AgentItemModel initialItem;
        private long nextSequence = 11L;
        private boolean hideInitialRequest;
        private AgentTurnModel lockedDuplicate;

        private TurnStore(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            return Optional.ofNullable(turn).filter(value -> value.turnId().equals(turnId));
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            calls.add("turn.find");
            if (hideInitialRequest) {
                return Optional.empty();
            }
            return Optional.ofNullable(turn)
                    .filter(value -> value.clientRequestId().equals(clientRequestId));
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequestForUpdate(String userId, String clientRequestId) {
            calls.add("turn.find.lock");
            return Optional.ofNullable(lockedDuplicate);
        }

        @Override
        public void createTurn(AgentTurnModel next) {
            turn = next;
        }

        @Override
        public long createTurnWithInitialItem(AgentTurnModel next, AgentItemModel initialItem) {
            calls.add("turn.createWithItem");
            turn = next;
            this.initialItem = initialItem;
            return nextSequence++;
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
            turn = next;
            return true;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return turn == null ? List.of() : List.of(turn);
        }
    }

    private static final class RecordingTransactionManager
            implements org.springframework.transaction.PlatformTransactionManager {
        private final QuestionStore questions;
        private final TurnStore turns;
        private int commits;
        private int rollbacks;
        private AgentWorkflowQuestionModel questionSnapshot;
        private AgentTurnModel turnSnapshot;
        private AgentItemModel itemSnapshot;
        private long sequenceSnapshot;

        private RecordingTransactionManager(QuestionStore questions, TurnStore turns) {
            this.questions = questions;
            this.turns = turns;
        }

        @Override
        public @NonNull TransactionStatus getTransaction(TransactionDefinition definition) {
            questionSnapshot = questions.question;
            turnSnapshot = turns.turn;
            itemSnapshot = turns.initialItem;
            sequenceSnapshot = turns.nextSequence;
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
            turns.initialItem = itemSnapshot;
            turns.nextSequence = sequenceSnapshot;
        }
    }
}
