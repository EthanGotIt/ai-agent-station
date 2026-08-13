package cn.ethan.core.agent.service;

import cn.ethan.core.agent.enums.AgentStatusEnum;
import cn.ethan.core.agent.enums.RouteTypeEnum;
import cn.ethan.core.agent.exception.SessionQueueException;
import cn.ethan.core.agent.model.AgentRequestModel;
import cn.ethan.core.agent.model.AgentResponseModel;
import cn.ethan.core.agent.model.QueuedExecutionModel;
import cn.ethan.core.agent.model.RequestHandleModel;
import cn.ethan.core.agent.support.CancellationToken;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session 执行队列管理器测试：验证 FIFO、并行、容量、超时和取消语义。
 *
 * @author ethan
 * @date 2026-08-06
 */
class SessionExecutionQueueManagerTest {

    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void closeExecutors() {
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void sameSessionExecutesStrictlyInFifoOrder() throws Exception {
        ExecutorService worker = register(Executors.newFixedThreadPool(2));
        SessionExecutionQueueManager manager = manager(4, 16, worker, Duration.ofSeconds(2));
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        QueuedExecutionModel first = submit(manager, "request-1", "user-1", "session-1", () -> {
            order.add("first-start");
            firstStarted.countDown();
            releaseFirst.await();
            order.add("first-end");
            return response("request-1");
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        QueuedExecutionModel second = submit(
                manager,
                "request-2",
                "user-1",
                "session-1",
                () -> {
                    order.add("second-start");
                    secondStarted.countDown();
                    return response("request-2");
                }
        );

        assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();
        first.completion().join();
        second.completion().join();

        assertEquals(List.of("first-start", "first-end", "second-start"), order);
    }

    @Test
    void differentSessionsCanExecuteInParallel() throws Exception {
        ExecutorService worker = register(Executors.newFixedThreadPool(2));
        SessionExecutionQueueManager manager = manager(4, 16, worker, Duration.ofSeconds(2));
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        QueuedExecutionModel first = submit(manager, "request-1", "user-1", "session-1", () -> {
            bothStarted.countDown();
            release.await();
            return response("request-1");
        });
        QueuedExecutionModel second = submit(manager, "request-2", "user-1", "session-2", () -> {
            bothStarted.countDown();
            release.await();
            return response("request-2");
        });

        assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
        release.countDown();
        first.completion().join();
        second.completion().join();
    }

    @Test
    void rejectsSessionAndGlobalCapacityOverflowBeforeExecution() {
        ManualExecutor manualExecutor = new ManualExecutor();
        SessionExecutionQueueManager sessionBounded = manager(
                4,
                16,
                manualExecutor,
                Duration.ofSeconds(2)
        );
        for (int index = 1; index <= 4; index++) {
            submit(
                    sessionBounded,
                    "session-request-" + index,
                    "user-1",
                    "session-1",
                    () -> response("accepted")
            );
        }

        SessionQueueException sessionFailure = assertThrows(
                SessionQueueException.class,
                () -> submit(
                        sessionBounded,
                        "session-request-5",
                        "user-1",
                        "session-1",
                        () -> response("rejected")
                )
        );
        assertEquals("SESSION_QUEUE_FULL", sessionFailure.getCode());
        assertEquals("session-request-1", sessionFailure.getRelatedRequestId());

        SessionExecutionQueueManager globalBounded = manager(
                2,
                2,
                manualExecutor,
                Duration.ofSeconds(2)
        );
        submit(globalBounded, "global-request-1", "user-1", "session-a", () -> response("a"));
        submit(globalBounded, "global-request-2", "user-1", "session-b", () -> response("b"));

        SessionQueueException globalFailure = assertThrows(
                SessionQueueException.class,
                () -> submit(
                        globalBounded,
                        "global-request-3",
                        "user-1",
                        "session-c",
                        () -> response("rejected")
                )
        );
        assertEquals("GLOBAL_QUEUE_FULL", globalFailure.getCode());
    }

    @Test
    void waitingRequestTimesOutWithoutExecuting() {
        ManualExecutor manualExecutor = new ManualExecutor();
        SessionExecutionQueueManager manager = manager(
                4,
                16,
                manualExecutor,
                Duration.ofMillis(50)
        );
        QueuedExecutionModel execution = submit(
                manager,
                "request-timeout",
                "user-1",
                "session-1",
                () -> response("unexpected")
        );

        CompletionException failure = assertThrows(
                CompletionException.class,
                execution.completion()::join
        );

        assertInstanceOf(SessionQueueException.class, failure.getCause());
        SessionQueueException queueFailure = (SessionQueueException) failure.getCause();
        assertEquals("QUEUE_WAIT_TIMEOUT", queueFailure.getCode());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void queuedCancellationSkipsExecutionAndKeepsFollowingRequest() {
        ManualExecutor manualExecutor = new ManualExecutor();
        SessionExecutionQueueManager manager = manager(
                4,
                16,
                manualExecutor,
                Duration.ofSeconds(2)
        );
        QueuedExecutionModel cancelled = submit(
                manager,
                "request-1",
                "user-1",
                "session-1",
                () -> response("unexpected")
        );
        QueuedExecutionModel following = submit(
                manager,
                "request-2",
                "user-1",
                "session-1",
                () -> response("following")
        );

        assertTrue(manager.cancelWaiting("request-1", "user-1"));
        assertEquals(AgentStatusEnum.CANCELLED, cancelled.completion().join().status());

        manualExecutor.runLast();
        assertEquals("following", following.completion().join().content());
    }

    @Test
    void initialWorkerRejectionIsReturnedBeforeAdmissionCompletes() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("worker is unavailable");
        };
        SessionExecutionQueueManager manager = manager(
                4,
                16,
                rejectingExecutor,
                Duration.ofSeconds(2)
        );

        SessionQueueException failure = assertThrows(
                SessionQueueException.class,
                () -> submit(
                        manager,
                        "request-rejected",
                        "user-1",
                        "session-1",
                        () -> response("unexpected")
                )
        );

        assertEquals("GLOBAL_QUEUE_FULL", failure.getCode());
        assertEquals(0, manager.pendingCount());
    }

    @Test
    void schedulerRejectionCancelsRequestAndRollsBackAdmission() {
        RejectingScheduler rejectingScheduler = new RejectingScheduler();
        try {
            CancellationToken token = new CancellationToken();
            SessionExecutionQueueManager manager = new SessionExecutionQueueManager(
                    4, 16, Duration.ofSeconds(2), Runnable::run, rejectingScheduler
            );

            SessionQueueException failure = assertThrows(
                    SessionQueueException.class,
                    () -> manager.submit(request("request-scheduler"), handle("request-scheduler", token),
                            () -> response("unexpected"))
            );

            assertEquals("GLOBAL_QUEUE_FULL", failure.getCode());
            assertTrue(token.isCancelled());
            assertEquals(0, manager.pendingCount());
        } finally {
            rejectingScheduler.shutdownNow();
        }
    }

    @Test
    void unexpectedWorkerAdmissionFailureDoesNotBlockFollowingRequest() {
        FirstExecutionFailsThenQueues worker = new FirstExecutionFailsThenQueues();
        SessionExecutionQueueManager manager = manager(4, 16, worker, Duration.ofSeconds(2));

        SessionQueueException failure = assertThrows(
                SessionQueueException.class,
                () -> submit(manager, "request-failed", "user-1", "session-1", () -> response("unexpected"))
        );
        assertEquals("GLOBAL_QUEUE_FULL", failure.getCode());
        assertEquals(0, manager.pendingCount());

        QueuedExecutionModel following = submit(
                manager, "request-following", "user-1", "session-1", () -> response("following")
        );
        worker.runQueued();

        assertEquals("following", following.completion().join().content());
        assertEquals(0, manager.pendingCount());
    }

    private SessionExecutionQueueManager manager(
            int maxPendingPerSession,
            int maxPendingGlobal,
            Executor worker,
            Duration waitTimeout
    ) {
        ScheduledExecutorService scheduler = register(
                Executors.newSingleThreadScheduledExecutor()
        );
        return new SessionExecutionQueueManager(
                maxPendingPerSession,
                maxPendingGlobal,
                waitTimeout,
                worker,
                scheduler
        );
    }

    private QueuedExecutionModel submit(
            SessionExecutionQueueManager manager,
            String requestId,
            String userId,
            String sessionId,
            java.util.concurrent.Callable<AgentResponseModel> execution
    ) {
        AgentRequestModel request = request(requestId);
        RequestHandleModel handle = new RequestHandleModel(requestId, userId, sessionId, new CancellationToken());
        return manager.submit(request, handle, execution);
    }

    private AgentRequestModel request(String requestId) {
        return new AgentRequestModel(requestId, "session-1", "message");
    }

    private RequestHandleModel handle(String requestId, CancellationToken token) {
        return new RequestHandleModel(requestId, "user-1", "session-1", token);
    }

    private AgentResponseModel response(String requestIdOrContent) {
        AgentRequestModel request = new AgentRequestModel(
                requestIdOrContent,
                "session",
                "message"
        );
        return AgentResponseModel.completed(
                request,
                RouteTypeEnum.ATOMIC,
                "test",
                requestIdOrContent
        );
    }

    private <T extends ExecutorService> T register(T executor) {
        executors.add(executor);
        return executor;
    }

    private static final class ManualExecutor implements Executor {

        private final List<Runnable> tasks = new CopyOnWriteArrayList<>();

        @Override
        public void execute(@NonNull Runnable command) {
            tasks.add(command);
        }

        private void runLast() {
            List<Runnable> snapshot = List.copyOf(tasks);
            tasks.clear();
            snapshot.get(snapshot.size() - 1).run();
        }
    }

    private static final class FirstExecutionFailsThenQueues implements Executor {

        private final List<Runnable> tasks = new ArrayList<>();
        private boolean rejectFirst = true;

        @Override
        public void execute(@NonNull Runnable command) {
            if (rejectFirst) {
                rejectFirst = false;
                throw new IllegalStateException("worker is unavailable");
            }
            tasks.add(command);
        }

        private void runQueued() {
            List<Runnable> snapshot = List.copyOf(tasks);
            tasks.clear();
            snapshot.forEach(Runnable::run);
        }
    }

    private static final class RejectingScheduler extends ScheduledThreadPoolExecutor {

        private RejectingScheduler() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit
        ) {
            throw new RejectedExecutionException("scheduler is unavailable");
        }
    }
}
