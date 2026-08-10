package cn.ethan.core.agent.port;

import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;
import cn.ethan.core.agent.model.AgentMemorySourceModel;

import java.util.List;
import java.util.Optional;

/**
 * Agent 记忆存储端口：首期按 userId + sessionId 隔离，保留未来跨会话扩展空间。
 *
 * @author ethan
 * @date 2026-08-09
 */
public interface AgentMemoryStore {

    void createSource(AgentMemorySourceModel source);

    void createEntry(AgentMemoryEntryModel entry);

    void appendEvidence(AgentMemoryEvidenceModel evidence);

    List<AgentMemoryEntryModel> list(String userId, String sessionId, boolean includeDeleted, int limit);

    Optional<AgentMemoryEntryModel> findOwned(String entryId, String userId, String sessionId);

    Optional<AgentMemoryEntryModel> findOwnedByKey(
            String userId,
            String sessionId,
            String category,
            String memoryKey
    );

    List<AgentMemoryEvidenceModel> listEvidence(String entryId, String userId, String sessionId);

    boolean update(AgentMemoryEntryModel expected, AgentMemoryEntryModel updated);
}
