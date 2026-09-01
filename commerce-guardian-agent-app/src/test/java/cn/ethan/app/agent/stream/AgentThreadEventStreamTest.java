package cn.ethan.app.agent.stream;

import cn.ethan.app.agent.api.AgentThreadEventDto;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSE 单连接契约测试：回放、实时事件和连接关闭必须保持顺序与去重边界。
 *
 * @author ethan
 * @date 2026-08-21
 */
class AgentThreadEventStreamTest {

    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void backlogAndBufferedLiveEventsAreOrderedAndDeduplicated() {
        List<String> delivered = new ArrayList<>();
        AgentThreadEventStream stream = new AgentThreadEventStream(
                "thread-1", 2, event -> delivered.add(event.eventId()));
        stream.accept(itemEvent("item-3", 3));
        stream.accept(itemEvent("item-4", 4));

        stream.replay(after -> after == 2
                        ? List.of(item("item-3", 3))
                        : List.of(),
                () -> ready("ready-1"));
        stream.accept(itemEvent("item-5", 5));

        assertEquals(List.of("item-3", "item-4", "ready-1", "item-5"), delivered);
    }

    @Test
    void concurrentPublishDuringReplayIsFlushedBeforeReady() throws Exception {
        List<String> delivered = new ArrayList<>();
        AgentThreadEventStream stream = new AgentThreadEventStream(
                "thread-1", 2, event -> delivered.add(event.eventId()));
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch releaseLoader = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> stream.replay(after -> {
                loaderEntered.countDown();
                await(releaseLoader);
                return after == 2 ? List.of(item("item-3", 3)) : List.of();
            }, () -> ready("ready-1")));
            assertTrue(loaderEntered.await(2, TimeUnit.SECONDS));
            stream.accept(itemEvent("item-4", 4));
            releaseLoader.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            stream.close();
        }

        assertEquals(List.of("item-3", "item-4", "ready-1"), delivered);
    }

    @Test
    void closeBeforeSubscriptionAttachmentClosesLateSubscription() {
        AgentThreadEventStream stream = new AgentThreadEventStream("thread-1", 0, event -> {
        });
        AtomicBoolean closed = new AtomicBoolean();

        stream.close();
        stream.attachSubscription(() -> closed.set(true));

        assertTrue(closed.get());
    }

    @Test
    void repeatedFullBacklogPageCannotKeepReplayLooping() {
        List<String> delivered = new ArrayList<>();
        AgentThreadEventStream stream = new AgentThreadEventStream(
                "thread-1", 0, event -> delivered.add(event.eventId()));
        List<AgentItemModel> repeatedPage = new ArrayList<>();
        for (int index = 0; index < 500; index++) {
            repeatedPage.add(item("item-3", 3));
        }
        AtomicInteger loaderCalls = new AtomicInteger();

        stream.replay(after -> {
            loaderCalls.incrementAndGet();
            return repeatedPage;
        }, () -> ready("ready-1"));

        assertEquals(2, loaderCalls.get());
        assertEquals(List.of("item-3", "ready-1"), delivered);
    }

    private AgentThreadEventGateway.AgentThreadEvent itemEvent(String eventId, long sequence) {
        return new AgentThreadEventGateway.AgentThreadEvent(
                eventId, "thread-1", "turn-1", "item.turn_state", "{}", sequence, NOW);
    }

    private AgentItemModel item(String itemId, long sequence) {
        return new AgentItemModel(itemId, "thread-1", "turn-1", sequence,
                AgentItemTypeEnum.TURN_STATE, "{\"status\":\"ACTIVE\"}", NOW);
    }

    private AgentThreadEventDto ready(String eventId) {
        return new AgentThreadEventDto(eventId, "thread-1", null, null,
                "ready", "{\"afterSequence\":4}", 4, NOW);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试回放未释放");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试线程被中断", interrupted);
        }
    }
}
