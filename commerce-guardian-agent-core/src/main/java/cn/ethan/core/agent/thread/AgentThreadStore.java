package cn.ethan.core.agent.thread;

import java.util.List;
import java.util.Optional;

/**
 * Thread 事实存储端口。
 *
 * @author ethan
 * @date 2026-08-19
 */
public interface AgentThreadStore {

    void createThread(AgentThreadModel thread);

    Optional<AgentThreadModel> findThread(String userId, String threadId);

    List<AgentThreadModel> listThreads(String userId);

    /**
     * 按数据库分页读取 Thread；默认实现保持轻量适配器和测试替身兼容。
     */
    default List<AgentThreadModel> listThreads(String userId, int offset, int limit) {
        return listThreads(userId).stream()
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 按生命周期读取 Thread；旧适配器默认在内存结果上过滤。 */
    default List<AgentThreadModel> listThreads(
            String userId,
            AgentThreadStatusEnum status,
            int offset,
            int limit
    ) {
        return listThreads(userId).stream()
                .filter(thread -> status == null || thread.status() == status)
                .skip(Math.max(0, offset))
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * 返回用户 Thread 总数，用于构造稳定分页响应。
     */
    default long countThreads(String userId) {
        return listThreads(userId).size();
    }

    /** 按生命周期统计 Thread；旧适配器默认在内存结果上过滤。 */
    default long countThreads(String userId, AgentThreadStatusEnum status) {
        return listThreads(userId).stream()
                .filter(thread -> status == null || thread.status() == status)
                .count();
    }

    void updateThread(AgentThreadModel thread);

}
