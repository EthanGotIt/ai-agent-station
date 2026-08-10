package cn.ethan.core.agent.support;

import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;
import cn.ethan.core.agent.model.AgentMemorySourceModel;
import cn.ethan.core.agent.port.AgentMemoryStore;

import java.util.List;
import java.util.Optional;

/**
 * 空记忆存储：当记录功能关闭时避免影响 Agent 正常执行。
 *
 * @author ethan
 * @date 2026-08-09
 */
public final class NoOpAgentMemoryStore implements AgentMemoryStore {

    @Override
    public void createSource(AgentMemorySourceModel source) {
        // disabled
    }

    @Override
    public void createEntry(AgentMemoryEntryModel entry) {
        // disabled
    }

    @Override
    public void appendEvidence(AgentMemoryEvidenceModel evidence) {
        // disabled
    }

    @Override
    public List<AgentMemoryEntryModel> list(
            String userId, String sessionId, boolean includeDeleted, int limit
    ) {
        return List.of();
    }

    @Override
    public Optional<AgentMemoryEntryModel> findOwned(String entryId, String userId, String sessionId) {
        return Optional.empty();
    }

    @Override
    public Optional<AgentMemoryEntryModel> findOwnedByKey(
            String userId, String sessionId, String category, String memoryKey
    ) {
        return Optional.empty();
    }

    @Override
    public List<AgentMemoryEvidenceModel> listEvidence(String entryId, String userId, String sessionId) {
        return List.of();
    }

    @Override
    public boolean update(AgentMemoryEntryModel expected, AgentMemoryEntryModel updated) {
        return false;
    }
}
