package cn.ethan.core.agent.thread;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 类型职责：管理 Thread 元数据和归属边界，不参与 Turn 执行调度。
 *
 * @author ethan
 * @date 2026-08-19
 */
public final class AgentThreadService {

    private final AgentThreadStore threads;
    private final AgentItemStore items;
    private final Clock clock;
    private final AgentThreadArchiveGuard archiveGuard;

    public AgentThreadService(AgentThreadStore threads, AgentItemStore items, Clock clock) {
        this(threads, items, clock, (userId, threadId) -> {
            // 内存测试和纯 Core 使用没有持久化活动表，保留原有创建边界。
        });
    }

    public AgentThreadService(
            AgentThreadStore threads,
            AgentItemStore items,
            Clock clock,
            AgentThreadArchiveGuard archiveGuard
    ) {
        this.threads = threads;
        this.items = items;
        this.clock = clock;
        this.archiveGuard = archiveGuard == null ? (userId, threadId) -> { } : archiveGuard;
    }

    public AgentThreadModel create(String userId, String title, String contextType, String contextId) {
        String normalizedUserId = requireIdentity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        Instant now = clock.instant();
        AgentThreadModel thread = new AgentThreadModel(
                UUID.randomUUID().toString(), normalizedUserId, title, AgentThreadStatusEnum.ACTIVE,
                normalize(contextType), normalize(contextId), 0L, now, now
        );
        threads.createThread(thread);
        return thread;
    }

    public AgentThreadPageModel listPage(String userId, int page, int size) {
        return listPage(userId, AgentThreadStatusEnum.ACTIVE, page, size);
    }

    public AgentThreadPageModel listPage(
            String userId,
            AgentThreadStatusEnum status,
            int page,
            int size
    ) {
        String normalizedUserId = requireIdentity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        AgentThreadStatusEnum normalizedStatus = status == null ? AgentThreadStatusEnum.ACTIVE : status;
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        long offsetValue = (long) safePage * safeSize;
        if (offsetValue > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("page 超出可读取范围");
        }
        int offset = (int) offsetValue;
        return new AgentThreadPageModel(
                threads.listThreads(normalizedUserId, normalizedStatus, offset, safeSize),
                safePage,
                safeSize,
                threads.countThreads(normalizedUserId, normalizedStatus)
        );
    }

    public AgentThreadModel get(String userId, String threadId) {
        String normalizedUserId = requireIdentity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        String normalizedThreadId = requireIdentity(threadId, "threadId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        return threads.findThread(normalizedUserId, normalizedThreadId)
                .orElseThrow(() -> new AgentThreadNotFoundException(normalizedThreadId));
    }

    public AgentThreadModel update(String userId, String threadId, String title, boolean archive) {
        AgentThreadModel current = get(userId, threadId);
        if (archive && current.status() == AgentThreadStatusEnum.ACTIVE) {
            archiveGuard.ensureCanArchive(current.userId(), current.threadId());
        }
        AgentThreadModel updated = new AgentThreadModel(
                current.threadId(), current.userId(), title == null ? current.title() : title,
                archive ? AgentThreadStatusEnum.ARCHIVED : AgentThreadStatusEnum.ACTIVE,
                current.contextType(), current.contextId(), current.nextSequence(), current.createdAt(), clock.instant(),
                current.openQuestionId(), current.openInteractionType(), current.openInteractionId()
        );
        threads.updateThread(updated);
        return updated;
    }

    public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
        AgentThreadModel thread = get(userId, threadId);
        return items.listItems(thread.userId(), thread.threadId(), Math.max(0L, afterSequence),
                Math.max(1, Math.min(limit, 501)));
    }

    private String requireIdentity(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
