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

    public AgentThreadService(AgentThreadStore threads, AgentItemStore items, Clock clock) {
        this.threads = threads;
        this.items = items;
        this.clock = clock;
    }

    public AgentThreadModel create(String userId, String title, String contextType, String contextId) {
        requireText(userId, "userId");
        Instant now = clock.instant();
        AgentThreadModel thread = new AgentThreadModel(
                UUID.randomUUID().toString(), userId, title, AgentThreadStatusEnum.ACTIVE,
                normalize(contextType), normalize(contextId), 0L, now, now
        );
        threads.createThread(thread);
        return thread;
    }

    public List<AgentThreadModel> list(String userId) {
        requireText(userId, "userId");
        return threads.listThreads(userId);
    }

    public AgentThreadPageModel listPage(String userId, int page, int size) {
        requireText(userId, "userId");
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        int offset = Math.multiplyExact(safePage, safeSize);
        return new AgentThreadPageModel(
                threads.listThreads(userId, offset, safeSize),
                safePage,
                safeSize,
                threads.countThreads(userId)
        );
    }

    public AgentThreadModel get(String userId, String threadId) {
        requireText(userId, "userId");
        requireText(threadId, "threadId");
        return threads.findThread(userId, threadId)
                .orElseThrow(() -> new AgentThreadNotFoundException(threadId));
    }

    public AgentThreadModel update(String userId, String threadId, String title, boolean archive) {
        AgentThreadModel current = get(userId, threadId);
        AgentThreadModel updated = new AgentThreadModel(
                current.threadId(), current.userId(), title == null ? current.title() : title,
                archive ? AgentThreadStatusEnum.ARCHIVED : AgentThreadStatusEnum.ACTIVE,
                current.contextType(), current.contextId(), current.nextSequence(), current.createdAt(), clock.instant()
        );
        threads.updateThread(updated);
        return updated;
    }

    public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
        get(userId, threadId);
        return items.listItems(userId, threadId, Math.max(0L, afterSequence), Math.max(1, Math.min(limit, 500)));
    }

    private void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.trim().length() > 256) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 256");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
