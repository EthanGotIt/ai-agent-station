package cn.ethan.app.agent.stream;

import cn.ethan.app.agent.api.AgentThreadEventDto;
import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 类型职责：为单个 SSE 连接串行合并持久化回放和实时事件，保证游标去重与切换顺序。
 *
 * @author ethan
 * @date 2026-08-21
 */
public final class AgentThreadEventStream implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AgentThreadEventStream.class);
    private static final int BACKLOG_PAGE_SIZE = 500;

    private final String threadId;
    private final Consumer<AgentThreadEventDto> sender;
    private final Object monitor = new Object();
    private final List<AgentThreadEventGateway.AgentThreadEvent> bufferedLiveEvents = new ArrayList<>();
    private final Set<String> deliveredEventIds = new HashSet<>();
    private long cursor;
    private boolean replaying = true;
    private boolean closed;
    private AutoCloseable subscription;
    private ScheduledFuture<?> heartbeat;

    public AgentThreadEventStream(String threadId, long afterSequence, Consumer<AgentThreadEventDto> sender) {
        if (threadId == null || threadId.isBlank() || sender == null) {
            throw new IllegalArgumentException("SSE Thread 和发送器不能为空");
        }
        this.threadId = threadId;
        this.cursor = Math.max(0, afterSequence);
        this.sender = sender;
    }

    /**
     * 接收事件总线事件；回放期间只入缓冲，不直接触碰 SseEmitter。
     */
    public void accept(AgentThreadEventGateway.AgentThreadEvent event) {
        if (event == null || !threadId.equals(event.threadId())) {
            return;
        }
        synchronized (monitor) {
            if (closed || alreadySeen(event.eventId(), event.sequence())) {
                return;
            }
            if (replaying) {
                bufferedLiveEvents.add(event);
                return;
            }
            deliverEventLocked(event);
        }
    }

    /**
     * 先读取完整 backlog，再按事件序列冲刷回放期间的实时缓冲，最后标记为 live。
     */
    public void replay(
            Function<Long, List<AgentItemModel>> backlogLoader,
            Supplier<AgentThreadEventDto> readyEvent
    ) {
        if (backlogLoader == null || readyEvent == null) {
            throw new IllegalArgumentException("SSE 回放器和 ready 事件不能为空");
        }
        for (;;) {
            List<AgentItemModel> backlog = backlogLoader.apply(currentCursor());
            if (backlog == null || backlog.isEmpty()) {
                break;
            }
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                for (AgentItemModel item : backlog) {
                    deliverItemLocked(item);
                }
            }
            if (backlog.size() < BACKLOG_PAGE_SIZE) {
                break;
            }
        }
        synchronized (monitor) {
            if (closed) {
                return;
            }
            bufferedLiveEvents.sort(Comparator.comparingLong(
                    event -> event.sequence() < 0 ? Long.MAX_VALUE : event.sequence()));
            for (AgentThreadEventGateway.AgentThreadEvent event : bufferedLiveEvents) {
                deliverEventLocked(event);
            }
            bufferedLiveEvents.clear();
            deliverControlLocked(readyEvent.get());
            replaying = false;
        }
    }

    /**
     * 发布不参与 Item 游标比较的控制事件，例如 heartbeat。
     */
    public void publishControl(AgentThreadEventDto event) {
        synchronized (monitor) {
            if (!closed) {
                deliverControlLocked(event);
            }
        }
    }

    public long currentCursor() {
        synchronized (monitor) {
            return cursor;
        }
    }

    public void attachSubscription(AutoCloseable nextSubscription) {
        boolean closeNow;
        synchronized (monitor) {
            closeNow = closed;
            if (!closeNow) {
                subscription = nextSubscription;
            }
        }
        if (closeNow) {
            closeQuietly(nextSubscription);
        }
    }

    public void attachHeartbeat(ScheduledFuture<?> nextHeartbeat) {
        boolean cancelNow;
        synchronized (monitor) {
            cancelNow = closed;
            if (!cancelNow) {
                heartbeat = nextHeartbeat;
            }
        }
        if (cancelNow && nextHeartbeat != null) {
            nextHeartbeat.cancel(false);
        }
    }

    @Override
    public void close() {
        AutoCloseable currentSubscription;
        ScheduledFuture<?> currentHeartbeat;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            bufferedLiveEvents.clear();
            currentSubscription = subscription;
            currentHeartbeat = heartbeat;
            subscription = null;
            heartbeat = null;
        }
        if (currentHeartbeat != null) {
            currentHeartbeat.cancel(false);
        }
        closeQuietly(currentSubscription);
    }

    private void deliverItemLocked(AgentItemModel item) {
        if (item == null || !threadId.equals(item.threadId())) {
            return;
        }
        deliverLocked(new AgentThreadEventDto(
                item.itemId(), threadId, item.turnId(), item.itemId(),
                "item." + item.type().name().toLowerCase(), item.payloadJson(),
                item.sequence(), item.createdAt()), true);
    }

    private void deliverEventLocked(AgentThreadEventGateway.AgentThreadEvent event) {
        deliverLocked(AgentThreadEventDto.from(event), true);
    }

    private void deliverControlLocked(AgentThreadEventDto event) {
        if (event != null) {
            deliverLocked(event, false);
        }
    }

    private void deliverLocked(AgentThreadEventDto event, boolean advancesCursor) {
        if (closed || event == null || !threadId.equals(event.threadId())
                || (advancesCursor && alreadySeen(event.eventId(), event.sequence()))) {
            return;
        }
        if (event.eventId() != null) {
            deliveredEventIds.add(event.eventId());
        }
        try {
            sender.accept(event);
            if (advancesCursor && event.sequence() >= 0) {
                cursor = Math.max(cursor, event.sequence());
            }
        } catch (RuntimeException failure) {
            LOGGER.debug("SSE event send failed, eventType={}, errorType={}",
                    event.type(), failure.getClass().getSimpleName());
            close();
        }
    }

    private boolean alreadySeen(String eventId, long sequence) {
        return (eventId != null && deliveredEventIds.contains(eventId))
                || (sequence >= 0 && sequence <= cursor);
    }

    private void closeQuietly(AutoCloseable resource) {
        if (resource == null) {
            return;
        }
        try {
            resource.close();
        } catch (Exception failure) {
            LOGGER.debug("SSE resource close failed, errorType={}", failure.getClass().getSimpleName());
        }
    }
}
