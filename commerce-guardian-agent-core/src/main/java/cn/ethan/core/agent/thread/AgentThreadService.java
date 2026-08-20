package cn.ethan.core.agent.thread;

import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadNotFoundException;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStore;

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

    private final AgentThreadStore store;
    private final Clock clock;

    public AgentThreadService(AgentThreadStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public AgentThreadModel create(String userId, String title, String contextType, String contextId) {
        requireText(userId, "userId");
        Instant now = clock.instant();
        AgentThreadModel thread = new AgentThreadModel(
                UUID.randomUUID().toString(), userId, title, AgentThreadStatusEnum.ACTIVE,
                normalize(contextType), normalize(contextId), 0L, now, now
        );
        store.createThread(thread);
        return thread;
    }

    public List<AgentThreadModel> list(String userId) {
        requireText(userId, "userId");
        return store.listThreads(userId);
    }

    public AgentThreadModel get(String userId, String threadId) {
        requireText(userId, "userId");
        requireText(threadId, "threadId");
        return store.findThread(userId, threadId)
                .orElseThrow(() -> new AgentThreadNotFoundException(threadId));
    }

    public AgentThreadModel update(String userId, String threadId, String title, boolean archive) {
        AgentThreadModel current = get(userId, threadId);
        AgentThreadModel updated = new AgentThreadModel(
                current.threadId(), current.userId(), title == null ? current.title() : title,
                archive ? AgentThreadStatusEnum.ARCHIVED : AgentThreadStatusEnum.ACTIVE,
                current.contextType(), current.contextId(), current.nextSequence(), current.createdAt(), clock.instant()
        );
        store.updateThread(updated);
        return updated;
    }

    public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
        get(userId, threadId);
        return store.listItems(userId, threadId, Math.max(0L, afterSequence), Math.max(1, Math.min(limit, 500)));
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
