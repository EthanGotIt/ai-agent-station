package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextAssembler;
import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import cn.ethan.core.agent.coordination.AgentTurnCoordinator;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentThreadStore;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentWorkflowAnswerInput;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类型职责：验证回答 Turn 的取消释放、重启恢复、提交失败和普通消息二次门禁。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentTurnRuntimeWorkflowAnswerTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void answerAdmissionUsesRealVersionAndDoubleClickIsIdempotent() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();

        AgentTurnModel first = fixture.runtime.answerQuestion(
                "user-1", fixture.thread.threadId(), "request-1", "run-1",
                "question-1", "checkpoint-1", 0, Map.of("decision", "APPROVE"));
        AgentTurnModel duplicate = fixture.runtime.answerQuestion(
                "user-1", fixture.thread.threadId(), "request-1", "run-1",
                "question-1", "checkpoint-1", 0, Map.of("decision", "APPROVE"));

        assertEquals(first.turnId(), duplicate.turnId());
        assertEquals(2L, first.workflowAnswerInput().enqueuedQuestionVersion());
        assertEquals(2L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(1, fixture.persistence.admissionCalls);

        AgentThreadConflictException conflict = assertThrows(AgentThreadConflictException.class,
                () -> fixture.runtime.answerQuestion(
                        "user-1", fixture.thread.threadId(), "request-1", "run-1",
                        "question-1", "checkpoint-1", 0, Map.of("decision", "REJECT")));
        assertEquals("CLIENT_REQUEST_CONFLICT", conflict.code());
    }

    @Test
    void cancellingQueuedAnswerReleasesEnqueuedReservationAndInvalidatesOldTurn() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-cancel", Map.of("decision", "APPROVE"));

        assertTrue(fixture.runtime.cancel("user-1", answer.turnId()));

        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertNull(fixture.persistence.question.answerTurnId());
        assertEquals(AgentTurnStatusEnum.CANCELLED,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
        assertFalse(fixture.persistence.closeAnswerTurn(
                "user-1", "question-1", 2, answer.turnId(), NOW));
    }

    @Test
    void queueWaitTimeoutReleasesAnswerReservation() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-timeout", Map.of("decision", "APPROVE"));

        fixture.scheduler.runNext();

        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.TIMED_OUT,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
    }

    @Test
    void activeCancellationBestEffortReleasesOnlyStillOpenQuestion() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-active-cancel", Map.of("decision", "APPROVE"));
        fixture.coordinator.onRun = () -> fixture.runtime.cancel("user-1", answer.turnId());

        fixture.executor.runAll();

        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.CANCELLED,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
    }

    @Test
    void submissionFailureAfterAdmissionReleasesAndFailsPersistedTurn() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        fixture.events.failWorkflowAnswer = true;

        assertThrows(IllegalStateException.class,
                () -> fixture.answer("request-submit-failure", Map.of("decision", "APPROVE")));

        AgentTurnModel failed = fixture.persistence.findTurnByRequest("user-1", "request-submit-failure")
                .orElseThrow();
        assertEquals(AgentTurnStatusEnum.FAILED, failed.status());
        assertEquals("ANSWER_SUBMISSION_FAILED", failed.errorCode());
        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
    }

    @Test
    void recoveryRebuildsQueueTaskFromPersistedStructuredAnswers() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentWorkflowAnswerAdmissionResult admitted = fixture.persistence.admit(
                fixture.command("request-recovery", Map.of("decision", "APPROVE", "reason", "重启恢复")));

        fixture.runtime.recoverPersistedTurns();
        fixture.executor.runAll();

        assertEquals(Map.of("decision", "APPROVE", "reason", "重启恢复"), fixture.coordinator.lastAnswers);
        assertEquals(AgentTurnStatusEnum.COMPLETED,
                fixture.persistence.findTurn("user-1", admitted.turn().turnId()).orElseThrow().status());
    }

    @Test
    void recoveryRejectsTurnWhoseReservationWasReleased() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentWorkflowAnswerAdmissionResult admitted = fixture.persistence.admit(
                fixture.command("request-stale", Map.of("decision", "APPROVE")));
        assertTrue(fixture.persistence.releaseAnswerTurn(
                "user-1", "question-1", 2, admitted.turn().turnId()));

        fixture.runtime.recoverPersistedTurns();
        fixture.executor.runAll();

        AgentTurnModel failed = fixture.persistence.findTurn("user-1", admitted.turn().turnId()).orElseThrow();
        assertEquals(AgentTurnStatusEnum.FAILED, failed.status());
        assertEquals("WORKFLOW_ANSWER_STALE", failed.errorCode());
        assertEquals(0, fixture.coordinator.calls);
    }

    @Test
    void ordinaryTurnRechecksQuestionImmediatelyBeforeCoordinator() {
        Fixture fixture = new Fixture();
        AgentTurnModel ordinary = fixture.runtime.submitTurn(
                "user-1", fixture.thread.threadId(), "request-ordinary", "继续处理");
        fixture.openQuestion();

        fixture.executor.runAll();

        AgentTurnModel failed = fixture.persistence.findTurn("user-1", ordinary.turnId()).orElseThrow();
        assertEquals(AgentTurnStatusEnum.FAILED, failed.status());
        assertEquals("THREAD_AWAITING_ANSWER", failed.errorCode());
        assertEquals(0, fixture.coordinator.calls);
    }

    @Test
    void engineFailureAtomicallyReleasesQuestionAndFailsAnswerTurn() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-engine-failure", Map.of("decision", "APPROVE"));
        fixture.coordinator.failure = new IllegalStateException("apiKey=DO_NOT_PERSIST");

        fixture.executor.runAll();

        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.FAILED,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
        assertTrue(fixture.persistence.items.stream()
                .filter(item -> answer.turnId().equals(item.turnId()))
                .noneMatch(item -> item.payloadJson().contains("DO_NOT_PERSIST")));
    }

    @Test
    void releaseFailureIsRetriedUntilQuestionBecomesAvailable() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-release-retry", Map.of("decision", "APPROVE"));
        fixture.persistence.reconciliationFailuresRemaining = 1;
        fixture.coordinator.failure = new IllegalStateException("模拟 Engine 数据库异常");

        fixture.executor.runAll();

        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.ACTIVE,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());

        fixture.scheduler.runAll();

        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.FAILED,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
    }

    @Test
    void restartReconcilesLegacyFailedTurnStillBoundToEnqueuedQuestion() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentWorkflowAnswerAdmissionResult admitted = fixture.persistence.admit(
                fixture.command("request-restart-reconcile", Map.of("decision", "APPROVE")));
        AgentTurnModel terminal = admitted.turn().terminal(
                AgentTurnStatusEnum.FAILED, "LEGACY_FAILURE", NOW);
        fixture.persistence.updateTurn(admitted.turn(), terminal);

        fixture.runtime.recoverPersistedTurns();

        assertEquals(3L, fixture.persistence.question.version());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(AgentTurnStatusEnum.FAILED,
                fixture.persistence.findTurn("user-1", admitted.turn().turnId()).orElseThrow().status());
    }

    @Test
    void restartReconcilesExpiredQueuedAnswerInsteadOfExecutingIt() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentWorkflowAnswerAdmissionResult admitted = fixture.persistence.admit(
                fixture.command("request-restart-expired", Map.of("decision", "APPROVE")));
        AgentTurnModel queued = admitted.turn();
        AgentTurnModel expired = new AgentTurnModel(
                queued.turnId(), queued.threadId(), queued.userId(), queued.clientRequestId(), queued.input(),
                queued.status(), queued.queuePosition(), queued.workflowRunId(), queued.errorCode(),
                NOW.minus(Duration.ofMinutes(6)), queued.startedAt(), queued.finishedAt(),
                queued.workflowAnswerInput(), queued.version() + 1);
        fixture.persistence.updateTurn(queued, expired);

        fixture.runtime.recoverPersistedTurns();
        fixture.executor.runAll();

        assertEquals(AgentTurnStatusEnum.TIMED_OUT,
                fixture.persistence.findTurn("user-1", queued.turnId()).orElseThrow().status());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
        assertEquals(0, fixture.coordinator.calls);
    }

    @Test
    void queueTimeoutRemovesTurnBeforeReleaseFailureAndRetriesOutOfBand() {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-queue-release-failure", Map.of("decision", "APPROVE"));
        fixture.persistence.reconciliationFailuresRemaining = 1;

        fixture.scheduler.runNext();
        fixture.executor.runAll();

        assertEquals(0, fixture.coordinator.calls);
        assertEquals(AgentTurnStatusEnum.QUEUED,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED,
                fixture.persistence.question.answerEnqueueStatus());

        fixture.scheduler.runAll();

        assertEquals(AgentTurnStatusEnum.TIMED_OUT,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
    }

    @Test
    void activeCancelIsVisibleBeforeBlockingReleaseAndPreventsNewSideEffect() throws Exception {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-blocking-cancel", Map.of("decision", "APPROVE"));
        fixture.coordinator.blockBeforeSideEffect = true;
        fixture.persistence.blockReconciliation = true;
        Thread executionThread = new Thread(fixture.executor::runAll);
        executionThread.start();
        assertTrue(fixture.coordinator.started.await(2, TimeUnit.SECONDS));

        Thread cancelThread = new Thread(() -> fixture.runtime.cancel("user-1", answer.turnId()));
        cancelThread.start();
        assertTrue(fixture.persistence.reconciliationEntered.await(2, TimeUnit.SECONDS));
        fixture.coordinator.continueExecution.countDown();
        assertTrue(fixture.coordinator.cancellationObserved.await(2, TimeUnit.SECONDS));

        assertEquals(0, fixture.coordinator.sideEffects.get());

        fixture.persistence.continueReconciliation.countDown();
        executionThread.join(2_000);
        cancelThread.join(2_000);
        assertFalse(executionThread.isAlive());
        assertFalse(cancelThread.isAlive());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
    }

    @Test
    void activeTimeoutIsVisibleBeforeBlockingReleaseAndPreventsNewSideEffect() throws Exception {
        Fixture fixture = new Fixture();
        fixture.openQuestion();
        AgentTurnModel answer = fixture.answer("request-blocking-timeout", Map.of("decision", "APPROVE"));
        fixture.coordinator.blockBeforeSideEffect = true;
        fixture.persistence.blockReconciliation = true;
        Thread executionThread = new Thread(fixture.executor::runAll);
        executionThread.start();
        assertTrue(fixture.coordinator.started.await(2, TimeUnit.SECONDS));
        fixture.scheduler.runNext();

        Thread timeoutThread = new Thread(fixture.scheduler::runNext);
        timeoutThread.start();
        assertTrue(fixture.persistence.reconciliationEntered.await(2, TimeUnit.SECONDS));
        fixture.coordinator.continueExecution.countDown();
        assertTrue(fixture.coordinator.cancellationObserved.await(2, TimeUnit.SECONDS));

        assertEquals(0, fixture.coordinator.sideEffects.get());

        fixture.persistence.continueReconciliation.countDown();
        executionThread.join(2_000);
        timeoutThread.join(2_000);
        assertFalse(executionThread.isAlive());
        assertFalse(timeoutThread.isAlive());
        assertEquals(AgentTurnStatusEnum.TIMED_OUT,
                fixture.persistence.findTurn("user-1", answer.turnId()).orElseThrow().status());
        assertEquals(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                fixture.persistence.question.answerEnqueueStatus());
    }

    @Test
    void questionGateFailureConvergesOrdinaryTurnWithoutCoordinatorExecution() {
        Fixture fixture = new Fixture();
        AgentTurnModel ordinary = fixture.runtime.submitTurn(
                "user-1", fixture.thread.threadId(), "request-gate-failure", "继续处理");
        fixture.persistence.openQuestionFailuresRemaining = 1;

        fixture.executor.runAll();

        AgentTurnModel failed = fixture.persistence.findTurn("user-1", ordinary.turnId()).orElseThrow();
        assertEquals(AgentTurnStatusEnum.FAILED, failed.status());
        assertEquals("QUESTION_GATE_FAILED", failed.errorCode());
        assertEquals(0, fixture.coordinator.calls);
    }

    private static final class Fixture {
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        private final Persistence persistence = new Persistence();
        private final ManualExecutor executor = new ManualExecutor();
        private final ManualScheduler scheduler = new ManualScheduler();
        private final RecordingEvents events = new RecordingEvents();
        private final CapturingCoordinator coordinator = new CapturingCoordinator();
        private final AgentThreadModel thread;
        private final AgentTurnRuntimeService runtime;

        private Fixture() {
            AgentThreadService threads = new AgentThreadService(persistence, persistence, clock);
            thread = threads.create("user-1", "Workflow Test", null, null);
            AgentContextAssembler context = new AgentContextAssembler(
                    persistence, persistence, clock, 2_000, 1_000, 256, 128);
            runtime = new AgentTurnRuntimeService(
                    persistence, persistence, persistence, persistence, persistence, persistence,
                    threads, context, coordinator, events, executor, scheduler, clock,
                    4, 16, Duration.ofMinutes(5), Duration.ofMinutes(5), 256);
        }

        private void openQuestion() {
            persistence.saveQuestion(new AgentWorkflowQuestionModel(
                    "run-1", thread.threadId(), "origin-turn-1", "user-1",
                    "question-1", "checkpoint-1", 0, "确认", "请确认", "[]",
                    AgentWorkflowQuestionStatusEnum.OPEN, NOW, null, null,
                    AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE,
                    List.of(
                            new AgentWorkflowQuestionFieldModel(
                                    "decision", true, 32, List.of("APPROVE", "REJECT")),
                            new AgentWorkflowQuestionFieldModel("reason", false, 128, List.of()))));
        }

        private AgentTurnModel answer(String requestId, Map<String, String> answers) {
            return runtime.answerQuestion(
                    "user-1", thread.threadId(), requestId, "run-1",
                    "question-1", "checkpoint-1", 0, answers);
        }

        private AgentWorkflowAnswerAdmissionCommand command(String requestId, Map<String, String> answers) {
            return new AgentWorkflowAnswerAdmissionCommand(
                    "user-1", thread.threadId(), requestId, 1, "run-1",
                    "question-1", "checkpoint-1", 0, answers);
        }
    }

    private static final class CapturingCoordinator implements AgentTurnCoordinator {
        private int calls;
        private Map<String, String> lastAnswers = Map.of();
        private Runnable onRun = () -> { };
        private RuntimeException failure;
        private boolean blockBeforeSideEffect;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch continueExecution = new CountDownLatch(1);
        private final CountDownLatch cancellationObserved = new CountDownLatch(1);
        private final AtomicInteger sideEffects = new AtomicInteger();

        @Override
        public AgentCoordinatorResult run(
                AgentThreadModel thread,
                AgentTurnModel turn,
                List<AgentItemModel> context,
                Map<String, String> answer
        ) {
            calls++;
            lastAnswers = Map.copyOf(answer);
            onRun.run();
            if (failure != null) throw failure;
            return new AgentCoordinatorResult("完成", List.of(), null, turn.workflowRunId(), false);
        }

        @Override
        public AgentCoordinatorResult run(
                AgentThreadModel thread,
                AgentTurnModel turn,
                List<AgentItemModel> context,
                Map<String, String> answer,
                AgentExecutionContext executionContext
        ) {
            if (!blockBeforeSideEffect) {
                return AgentTurnCoordinator.super.run(thread, turn, context, answer, executionContext);
            }
            calls++;
            lastAnswers = Map.copyOf(answer);
            started.countDown();
            await(continueExecution);
            try {
                executionContext.checkActive();
            } catch (AgentExecutionCancelledException cancelled) {
                cancellationObserved.countDown();
                throw cancelled;
            }
            sideEffects.incrementAndGet();
            return new AgentCoordinatorResult("完成", List.of(), null, turn.workflowRunId(), false);
        }

        private void await(CountDownLatch latch) {
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试并发闩锁等待超时");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("测试线程被中断", interrupted);
            }
        }
    }

    private static final class RecordingEvents implements AgentThreadEventGateway {
        private boolean failWorkflowAnswer;

        @Override
        public void publish(AgentThreadEvent event) {
            if (failWorkflowAnswer && "item.workflow_answer".equals(event.type())) {
                throw new IllegalStateException("模拟提交到内存队列前的事件发布失败");
            }
        }
    }

    private static final class ManualExecutor implements java.util.concurrent.Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(@NonNull Runnable command) {
            tasks.add(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }
    }

    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        private final List<ManualScheduledFuture> scheduled = new ArrayList<>();

        private ManualScheduler() {
            super(1);
        }

        @Override
        public @NonNull ScheduledFuture<?> schedule(@NonNull Runnable command, long delay, @NonNull TimeUnit unit) {
            ManualScheduledFuture future = new ManualScheduledFuture(command);
            scheduled.add(future);
            return future;
        }

        private void runNext() {
            scheduled.remove(0).run();
        }

        private void runAll() {
            while (!scheduled.isEmpty()) {
                scheduled.remove(0).run();
            }
        }
    }

    private static final class ManualScheduledFuture extends FutureTask<Void> implements ScheduledFuture<Void> {

        private ManualScheduledFuture(Runnable command) {
            super(command, null);
        }

        @Override
        public long getDelay(@NonNull TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.@NonNull Delayed other) {
            return 0;
        }
    }

    private static final class Persistence implements AgentThreadStore, AgentTurnStore, AgentItemStore,
            AgentWorkflowQuestionStore, AgentContextSnapshotStore, AgentWorkflowAnswerAdmission,
            AgentWorkflowAnswerFailureReconciler {
        private final Map<String, AgentThreadModel> threads = new HashMap<>();
        private final Map<String, AgentTurnModel> turns = new HashMap<>();
        private final List<AgentItemModel> items = new ArrayList<>();
        private AgentWorkflowQuestionModel question;
        private int admissionCalls;
        private int reconciliationFailuresRemaining;
        private int openQuestionFailuresRemaining;
        private boolean blockReconciliation;
        private final CountDownLatch reconciliationEntered = new CountDownLatch(1);
        private final CountDownLatch continueReconciliation = new CountDownLatch(1);

        @Override
        public AgentWorkflowAnswerAdmissionResult admit(AgentWorkflowAnswerAdmissionCommand command) {
            admissionCalls++;
            Optional<AgentTurnModel> duplicate = findTurnByRequest(command.userId(), command.clientRequestId());
            if (duplicate.isPresent()) {
                AgentWorkflowAnswerInput input = duplicate.get().workflowAnswerInput();
                return new AgentWorkflowAnswerAdmissionResult(
                        duplicate.get(), input.enqueuedQuestionVersion(), null, false);
            }
            OptionalLong reserved = reserveAnswerTurn(
                    command.userId(), command.questionId(), command.expectedVersion(), "answer-turn-" + admissionCalls);
            if (reserved.isEmpty()) {
                throw new AgentThreadConflictException("WORKFLOW_VERSION_CONFLICT", "QuestionCard 版本已变化");
            }
            long enqueuedVersion = reserved.getAsLong() + 1;
            Map<String, String> validatedAnswers = question.validateAnswers(command.answers());
            AgentWorkflowAnswerInput input = new AgentWorkflowAnswerInput(
                    command.runId(), command.questionId(), command.checkpointId(),
                    enqueuedVersion, validatedAnswers);
            AgentTurnModel turn = new AgentTurnModel(
                    "answer-turn-" + admissionCalls, command.threadId(), command.userId(), command.clientRequestId(),
                    "QuestionCard 回答", AgentTurnStatusEnum.QUEUED, command.queuePosition(), command.runId(),
                    null, NOW, null, null, input);
            AgentItemModel item = new AgentItemModel(
                    "answer-item-" + admissionCalls, command.threadId(), turn.turnId(), 0,
                    AgentItemTypeEnum.WORKFLOW_ANSWER,
                    "{\"schemaVersion\":1,\"kind\":\"WORKFLOW_ANSWER\",\"data\":{}}", NOW);
            long sequence = createTurnWithInitialItem(turn, item);
            OptionalLong enqueued = markAnswerTurnEnqueued(
                    command.userId(), command.questionId(), reserved.getAsLong(), turn.turnId());
            if (enqueued.isEmpty()) {
                throw new AgentThreadConflictException("WORKFLOW_VERSION_CONFLICT", "QuestionCard 版本已变化");
            }
            AgentItemModel persistedItem = new AgentItemModel(
                    item.itemId(), item.threadId(), item.turnId(), sequence, item.type(), item.payload(), item.createdAt());
            return new AgentWorkflowAnswerAdmissionResult(turn, enqueued.getAsLong(), persistedItem, true);
        }

        @Override
        public void createThread(AgentThreadModel thread) {
            threads.put(thread.threadId(), thread);
        }

        @Override
        public Optional<AgentThreadModel> findThread(String userId, String threadId) {
            return Optional.ofNullable(threads.get(threadId)).filter(value -> value.userId().equals(userId));
        }

        @Override
        public List<AgentThreadModel> listThreads(String userId) {
            return threads.values().stream().filter(value -> value.userId().equals(userId)).toList();
        }

        @Override
        public void updateThread(AgentThreadModel thread) {
            threads.put(thread.threadId(), thread);
        }

        @Override
        public Optional<AgentTurnModel> findTurn(String userId, String turnId) {
            return Optional.ofNullable(turns.get(turnId)).filter(value -> value.userId().equals(userId));
        }

        @Override
        public Optional<AgentTurnModel> findTurnByRequest(String userId, String clientRequestId) {
            return turns.values().stream()
                    .filter(value -> value.userId().equals(userId)
                            && value.clientRequestId().equals(clientRequestId))
                    .findFirst();
        }

        @Override
        public void createTurn(AgentTurnModel turn) {
            turns.put(turn.turnId(), turn);
        }

        @Override
        public long createTurnWithInitialItem(AgentTurnModel turn, AgentItemModel initialItem) {
            createTurn(turn);
            return appendItem(initialItem);
        }

        @Override
        public boolean updateTurn(AgentTurnModel expected, AgentTurnModel next) {
            AgentTurnModel current = turns.get(expected.turnId());
            if (current == null || current.version() != expected.version()
                    || next.version() != expected.version() + 1) {
                return false;
            }
            turns.put(next.turnId(), next);
            return true;
        }

        @Override
        public List<AgentTurnModel> listRecoverableTurns() {
            return turns.values().stream()
                    .filter(value -> value.status() == AgentTurnStatusEnum.QUEUED
                            || value.status() == AgentTurnStatusEnum.ACTIVE)
                    .toList();
        }

        @Override
        public List<AgentTurnModel> listWorkflowAnswerReconciliationCandidates() {
            if (question == null || question.status() != AgentWorkflowQuestionStatusEnum.OPEN
                    || question.answerEnqueueStatus()
                    != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED) {
                return List.of();
            }
            return turns.values().stream()
                    .filter(value -> value.turnId().equals(question.answerTurnId()))
                    .filter(value -> value.status() == AgentTurnStatusEnum.FAILED
                            || value.status() == AgentTurnStatusEnum.CANCELLED
                            || value.status() == AgentTurnStatusEnum.TIMED_OUT)
                    .toList();
        }

        @Override
        public long appendItem(AgentItemModel item) {
            long sequence = items.stream().filter(value -> value.threadId().equals(item.threadId()))
                    .mapToLong(AgentItemModel::sequence).max().orElse(0) + 1;
            items.add(new AgentItemModel(
                    item.itemId(), item.threadId(), item.turnId(), sequence,
                    item.type(), item.payload(), item.createdAt()));
            return sequence;
        }

        @Override
        public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
            return items.stream()
                    .filter(value -> value.threadId().equals(threadId) && value.sequence() > afterSequence)
                    .sorted(Comparator.comparingLong(AgentItemModel::sequence))
                    .limit(limit)
                    .toList();
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
            if (openQuestionFailuresRemaining > 0) {
                openQuestionFailuresRemaining--;
                throw new IllegalStateException("模拟 Question 门禁查询失败");
            }
            return Optional.ofNullable(question)
                    .filter(value -> value.status() == AgentWorkflowQuestionStatusEnum.OPEN)
                    .filter(value -> value.userId().equals(userId) && value.threadId().equals(threadId));
        }

        @Override
        public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
            return Optional.ofNullable(question)
                    .filter(value -> value.status() == AgentWorkflowQuestionStatusEnum.OPEN)
                    .filter(value -> value.userId().equals(userId) && value.runId().equals(runId));
        }

        @Override
        public void saveQuestion(AgentWorkflowQuestionModel next) {
            question = next;
        }

        @Override
        public OptionalLong reserveAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            if (question == null || question.version() != expectedVersion
                    || !question.userId().equals(userId) || !question.questionId().equals(questionId)
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
            if (question == null || question.version() != expectedVersion
                    || !answerTurnId.equals(question.answerTurnId())
                    || question.answerEnqueueStatus()
                    != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED) {
                return OptionalLong.empty();
            }
            question = question.answerTurnEnqueued();
            return OptionalLong.of(question.version());
        }

        @Override
        public boolean releaseAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId
        ) {
            if (question == null || question.version() != expectedVersion
                    || !question.userId().equals(userId) || !question.questionId().equals(questionId)
                    || !answerTurnId.equals(question.answerTurnId())
                    || question.status() != AgentWorkflowQuestionStatusEnum.OPEN) {
                return false;
            }
            question = question.releaseAnswerTurn();
            return true;
        }

        @Override
        public boolean closeAnswerTurn(
                String userId, String questionId, long expectedVersion, String answerTurnId, Instant answeredAt
        ) {
            if (question == null || question.version() != expectedVersion
                    || !answerTurnId.equals(question.answerTurnId())
                    || question.answerEnqueueStatus()
                    != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED) {
                return false;
            }
            question = new AgentWorkflowQuestionModel(
                    question.runId(), question.threadId(), question.turnId(), question.userId(), question.questionId(),
                    question.checkpointId(), question.version() + 1, question.title(), question.prompt(),
                    question.fieldsJson(), AgentWorkflowQuestionStatusEnum.ANSWERED, question.createdAt(), answeredAt,
                    answerTurnId, AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED,
                    question.answerFields());
            return true;
        }

        @Override
        public synchronized boolean reconcile(
                AgentTurnModel turn,
                AgentTurnStatusEnum terminalStatus,
                String errorCode,
                Instant finishedAt
        ) {
            if (reconciliationFailuresRemaining > 0) {
                reconciliationFailuresRemaining--;
                throw new IllegalStateException("模拟 release 首次失败");
            }
            if (blockReconciliation) {
                reconciliationEntered.countDown();
                try {
                    if (!continueReconciliation.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("模拟阻塞 release 超时");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("模拟阻塞 release 被中断", interrupted);
                }
            }
            AgentWorkflowAnswerInput input = turn.workflowAnswerInput();
            if (input != null && question != null
                    && question.status() == AgentWorkflowQuestionStatusEnum.OPEN
                    && turn.turnId().equals(question.answerTurnId())
                    && question.version() == input.enqueuedQuestionVersion()) {
                question = question.releaseAnswerTurn();
            }
            turns.put(turn.turnId(), turn.terminal(terminalStatus, errorCode, finishedAt));
            return true;
        }

        @Override
        public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
            return Optional.empty();
        }

        @Override
        public void saveSnapshot(AgentContextSnapshotModel snapshot) {
        }
    }
}
