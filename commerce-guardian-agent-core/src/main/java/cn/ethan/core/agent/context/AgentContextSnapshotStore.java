package cn.ethan.core.agent.context;

import java.util.Optional;

/**
 * 类型职责：保存上下文摘要快照，允许长 Thread 在新 Turn 中复用压缩后的历史。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentContextSnapshotStore {

    Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId);

    void saveSnapshot(AgentContextSnapshotModel snapshot);
}
