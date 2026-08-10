package cn.ethan.infrastructure.memory.store;

import cn.ethan.core.agent.enums.AgentMemoryCategoryEnum;
import cn.ethan.core.agent.enums.AgentMemoryOriginEnum;
import cn.ethan.core.agent.model.AgentMemoryEntryModel;
import cn.ethan.core.agent.model.AgentMemoryEvidenceModel;
import cn.ethan.core.agent.model.AgentMemorySourceModel;
import cn.ethan.core.agent.port.AgentMemoryStore;
import cn.ethan.infrastructure.memory.entity.AgentMemoryEntryEntity;
import cn.ethan.infrastructure.memory.entity.AgentMemoryEvidenceEntity;
import cn.ethan.infrastructure.memory.entity.AgentMemorySourceEntity;
import cn.ethan.infrastructure.memory.mapper.AgentMemoryEntryMapper;
import cn.ethan.infrastructure.memory.mapper.AgentMemoryEvidenceMapper;
import cn.ethan.infrastructure.memory.mapper.AgentMemorySourceMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;

import java.util.List;
import java.util.Optional;

/**
 * MyBatis 记忆存储：所有读写均以 userId + sessionId 为归属条件。
 *
 * @author ethan
 * @date 2026-08-10
 */
public final class MybatisAgentMemoryStore implements AgentMemoryStore {

    private final AgentMemorySourceMapper sourceMapper;
    private final AgentMemoryEntryMapper entryMapper;
    private final AgentMemoryEvidenceMapper evidenceMapper;

    public MybatisAgentMemoryStore(
            AgentMemorySourceMapper sourceMapper,
            AgentMemoryEntryMapper entryMapper,
            AgentMemoryEvidenceMapper evidenceMapper
    ) {
        this.sourceMapper = sourceMapper;
        this.entryMapper = entryMapper;
        this.evidenceMapper = evidenceMapper;
    }

    @Override
    public void createSource(AgentMemorySourceModel source) {
        sourceMapper.insert(sourceEntity(source));
    }

    @Override
    public void createEntry(AgentMemoryEntryModel entry) {
        entryMapper.insert(entryEntity(entry));
    }

    @Override
    public void appendEvidence(AgentMemoryEvidenceModel evidence) {
        AgentMemoryEvidenceEntity entity = new AgentMemoryEvidenceEntity();
        entity.setEvidenceId(evidence.evidenceId());
        entity.setEntryId(evidence.entryId());
        entity.setEvidenceType(evidence.evidenceType());
        entity.setEvidenceRef(evidence.evidenceRef());
        entity.setCreatedAt(evidence.createdAt());
        evidenceMapper.insert(entity);
    }

    @Override
    public List<AgentMemoryEntryModel> list(
            String userId, String sessionId, boolean includeDeleted, int limit
    ) {
        LambdaQueryWrapper<AgentMemoryEntryEntity> query = new LambdaQueryWrapper<AgentMemoryEntryEntity>()
                .eq(AgentMemoryEntryEntity::getUserId, userId)
                .eq(AgentMemoryEntryEntity::getSessionId, sessionId);
        if (!includeDeleted) {
            query.eq(AgentMemoryEntryEntity::getDeleted, false);
        }
        return entryMapper.selectList(query.orderByDesc(AgentMemoryEntryEntity::getUpdatedAt)
                        .last("LIMIT " + Math.min(Math.max(limit, 1), 100)))
                .stream().map(this::model).toList();
    }

    @Override
    public Optional<AgentMemoryEntryModel> findOwned(String entryId, String userId, String sessionId) {
        return Optional.ofNullable(entryMapper.selectOne(new LambdaQueryWrapper<AgentMemoryEntryEntity>()
                .eq(AgentMemoryEntryEntity::getEntryId, entryId)
                .eq(AgentMemoryEntryEntity::getUserId, userId)
                .eq(AgentMemoryEntryEntity::getSessionId, sessionId))).map(this::model);
    }

    @Override
    public Optional<AgentMemoryEntryModel> findOwnedByKey(
            String userId, String sessionId, String category, String memoryKey
    ) {
        return Optional.ofNullable(entryMapper.selectOne(new LambdaQueryWrapper<AgentMemoryEntryEntity>()
                .eq(AgentMemoryEntryEntity::getUserId, userId)
                .eq(AgentMemoryEntryEntity::getSessionId, sessionId)
                .eq(AgentMemoryEntryEntity::getCategory, category)
                .eq(AgentMemoryEntryEntity::getMemoryKey, memoryKey))).map(this::model);
    }

    @Override
    public List<AgentMemoryEvidenceModel> listEvidence(String entryId, String userId, String sessionId) {
        if (findOwned(entryId, userId, sessionId).isEmpty()) {
            return List.of();
        }
        return evidenceMapper.selectList(new LambdaQueryWrapper<AgentMemoryEvidenceEntity>()
                        .eq(AgentMemoryEvidenceEntity::getEntryId, entryId)
                        .orderByAsc(AgentMemoryEvidenceEntity::getCreatedAt))
                .stream().map(entity -> new AgentMemoryEvidenceModel(
                        entity.getEvidenceId(), entity.getEntryId(), entity.getEvidenceType(),
                        entity.getEvidenceRef(), entity.getCreatedAt()
                )).toList();
    }

    @Override
    public boolean update(AgentMemoryEntryModel expected, AgentMemoryEntryModel updated) {
        return entryMapper.update(null, new LambdaUpdateWrapper<AgentMemoryEntryEntity>()
                .eq(AgentMemoryEntryEntity::getEntryId, expected.entryId())
                .eq(AgentMemoryEntryEntity::getUserId, expected.userId())
                .eq(AgentMemoryEntryEntity::getSessionId, expected.sessionId())
                .eq(AgentMemoryEntryEntity::getVersion, expected.version())
                .set(AgentMemoryEntryEntity::getSourceId, updated.sourceId())
                .set(AgentMemoryEntryEntity::getCategory, updated.category().name())
                .set(AgentMemoryEntryEntity::getMemoryKey, updated.memoryKey())
                .set(AgentMemoryEntryEntity::getMemoryValue, updated.value())
                .set(AgentMemoryEntryEntity::getOrigin, updated.origin().name())
                .set(AgentMemoryEntryEntity::getConfidence, updated.confidence())
                .set(AgentMemoryEntryEntity::getVersion, updated.version())
                .set(AgentMemoryEntryEntity::getDeleted, updated.deleted())
                .set(AgentMemoryEntryEntity::getExpiresAt, updated.expiresAt())
                .set(AgentMemoryEntryEntity::getUpdatedAt, updated.updatedAt())) == 1;
    }

    private AgentMemorySourceEntity sourceEntity(AgentMemorySourceModel source) {
        AgentMemorySourceEntity entity = new AgentMemorySourceEntity();
        entity.setSourceId(source.sourceId());
        entity.setUserId(source.userId());
        entity.setSessionId(source.sessionId());
        entity.setRequestId(source.requestId());
        entity.setSourceType(source.sourceType().name());
        entity.setCreatedAt(source.createdAt());
        return entity;
    }

    private AgentMemoryEntryEntity entryEntity(AgentMemoryEntryModel entry) {
        AgentMemoryEntryEntity entity = new AgentMemoryEntryEntity();
        entity.setEntryId(entry.entryId());
        entity.setSourceId(entry.sourceId());
        entity.setUserId(entry.userId());
        entity.setSessionId(entry.sessionId());
        entity.setCategory(entry.category().name());
        entity.setMemoryKey(entry.memoryKey());
        entity.setMemoryValue(entry.value());
        entity.setOrigin(entry.origin().name());
        entity.setConfidence(entry.confidence());
        entity.setVersion(entry.version());
        entity.setDeleted(entry.deleted());
        entity.setExpiresAt(entry.expiresAt());
        entity.setCreatedAt(entry.createdAt());
        entity.setUpdatedAt(entry.updatedAt());
        return entity;
    }

    private AgentMemoryEntryModel model(AgentMemoryEntryEntity entity) {
        return new AgentMemoryEntryModel(
                entity.getEntryId(), entity.getSourceId(), entity.getUserId(), entity.getSessionId(),
                AgentMemoryCategoryEnum.valueOf(entity.getCategory()), entity.getMemoryKey(),
                entity.getMemoryValue(), AgentMemoryOriginEnum.valueOf(entity.getOrigin()),
                entity.getConfidence() == null ? 0.0 : entity.getConfidence(),
                entity.getVersion() == null ? 0L : entity.getVersion(),
                Boolean.TRUE.equals(entity.getDeleted()), entity.getExpiresAt(),
                entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }
}
