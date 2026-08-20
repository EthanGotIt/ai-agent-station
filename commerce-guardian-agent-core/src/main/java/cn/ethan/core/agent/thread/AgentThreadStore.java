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

    void updateThread(AgentThreadModel thread);

}
