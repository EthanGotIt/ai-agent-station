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

    /**
     * 按归属读取快照链中的指定节点；实现必须同时校验 userId 与 threadId。
     */
    Optional<AgentContextSnapshotModel> findSnapshot(String userId, String threadId, String snapshotId);

    void saveSnapshot(AgentContextSnapshotModel snapshot);

    /**
     * 以最新快照标识作为 CAS 条件提交派生视图。旧适配器默认顺序写入，生产数据库适配器必须覆盖。
     */
    default boolean saveSnapshotIfCurrent(
            String userId,
            String threadId,
            String expectedLatestSnapshotId,
            AgentContextSnapshotModel snapshot
    ) {
        if (snapshot == null || !java.util.Objects.equals(threadId, snapshot.threadId())) {
            return false;
        }
        Optional<AgentContextSnapshotModel> current = findLatestSnapshot(userId, threadId);
        String currentId = current.map(AgentContextSnapshotModel::snapshotId).orElse(null);
        if (!java.util.Objects.equals(currentId, expectedLatestSnapshotId)) {
            return false;
        }
        saveSnapshot(snapshot);
        return true;
    }

}
