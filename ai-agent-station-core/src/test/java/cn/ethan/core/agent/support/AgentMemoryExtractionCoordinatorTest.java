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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            Thread.sleep(20);
            coordinator.schedule(input("request-2"));

            assertTrue(extracted.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("request-1", "request-2"), received.get().stream()
                    .map(AgentMemoryExtractionInputModel::requestId).toList());
        } finally {
            scheduler.shutdownNow();
        }
    }

    private static AgentMemoryExtractionInputModel input(String requestId) {
        return new AgentMemoryExtractionInputModel(
                "user-1", "session-1", requestId, AgentMemorySourceEnum.REACT, "问题", "答复"
        );
    }
}
