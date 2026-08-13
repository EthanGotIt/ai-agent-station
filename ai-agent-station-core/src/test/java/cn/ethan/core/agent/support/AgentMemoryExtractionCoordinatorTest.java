package cn.ethan.core.agent.support;

import cn.ethan.core.agent.enums.AgentMemorySourceEnum;
import cn.ethan.core.agent.model.AgentMemoryExtractionInputModel;
import cn.ethan.core.agent.service.AgentMemoryService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆提取协调器测试：验证同会话完成回合会在空闲窗口合并。
 *
 * @author ethan
 * @date 2026-08-10
 */
class AgentMemoryExtractionCoordinatorTest {

    @Test
    void debouncesAdjacentTurnsForSameSession() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch extracted = new CountDownLatch(1);
        AtomicReference<List<AgentMemoryExtractionInputModel>> received = new AtomicReference<>(List.of());
        AgentMemoryService memories = new AgentMemoryService(
                true, false, 0.75, new NoOpAgentMemoryStore(),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
        try (AgentMemoryExtractionCoordinator coordinator = new AgentMemoryExtractionCoordinator(
                Duration.ofMillis(80), scheduler, Runnable::run,
                inputs -> {
                    received.set(List.copyOf(inputs));
                    extracted.countDown();
                    return List.of();
                }, memories
        )) {
            coordinator.schedule(input("request-1"));
            coordinator.schedule(input("request-2"));

            assertTrue(extracted.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("request-1", "request-2"), received.get().stream()
                    .map(AgentMemoryExtractionInputModel::requestId).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void schedulerRejectionIsBestEffortAndDoesNotLeaveFirstBatch() {
        RejectingScheduler scheduler = new RejectingScheduler();
        try {
            AgentMemoryService memories = memories();
            try (AgentMemoryExtractionCoordinator coordinator = new AgentMemoryExtractionCoordinator(
                    Duration.ofSeconds(1), scheduler, Runnable::run, inputs -> List.of(), memories
            )) {
                assertDoesNotThrow(() -> coordinator.schedule(input("request-rejected")));
                assertDoesNotThrow(coordinator::close);
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectedRescheduleKeepsPreviousFutureAndMergedInputs() throws Exception {
        RejectSecondScheduleExecutor scheduler = new RejectSecondScheduleExecutor();
        CountDownLatch extracted = new CountDownLatch(1);
        AtomicReference<List<AgentMemoryExtractionInputModel>> received = new AtomicReference<>(List.of());
        try (AgentMemoryExtractionCoordinator coordinator = new AgentMemoryExtractionCoordinator(
                Duration.ofMillis(40), scheduler, Runnable::run,
                inputs -> {
                    received.set(List.copyOf(inputs));
                    extracted.countDown();
                    return List.of();
                }, memories()
        )) {
            coordinator.schedule(input("request-1"));
            coordinator.schedule(input("request-2"));

            assertTrue(extracted.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("request-1", "request-2"), received.get().stream()
                    .map(AgentMemoryExtractionInputModel::requestId).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void closeIsIdempotentAndCancelsPendingExtraction() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch extracted = new CountDownLatch(1);
        try (AgentMemoryExtractionCoordinator coordinator = new AgentMemoryExtractionCoordinator(
                Duration.ofSeconds(1), scheduler, Runnable::run,
                inputs -> {
                    extracted.countDown();
                    return List.of();
                }, memories()
        )) {
            coordinator.schedule(input("request-close"));
            coordinator.close();
            coordinator.close();

            assertFalse(extracted.await(150, TimeUnit.MILLISECONDS));
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static AgentMemoryService memories() {
        return new AgentMemoryService(
                true, false, 0.75, new NoOpAgentMemoryStore(),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC)
        );
    }

    private static AgentMemoryExtractionInputModel input(String requestId) {
        return new AgentMemoryExtractionInputModel(
                "user-1", "session-1", requestId, AgentMemorySourceEnum.REACT, "问题", "答复"
        );
    }

    private static final class RejectSecondScheduleExecutor extends ScheduledThreadPoolExecutor {

        private int calls;

        private RejectSecondScheduleExecutor() {
            super(1);
        }

        @Override
        public java.util.concurrent.ScheduledFuture<?> schedule(
                Runnable command,
                long delay,
                TimeUnit unit
        ) {
            calls++;
            if (calls == 2) {
                throw new java.util.concurrent.RejectedExecutionException("reschedule unavailable");
            }
            return super.schedule(command, delay, unit);
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
            throw new java.util.concurrent.RejectedExecutionException("scheduler unavailable");
        }
    }
}
