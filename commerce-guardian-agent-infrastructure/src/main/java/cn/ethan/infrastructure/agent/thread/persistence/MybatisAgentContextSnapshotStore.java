package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 类型职责：持久化 Thread 的版本化上下文摘要快照。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public class MybatisAgentContextSnapshotStore implements AgentContextSnapshotStore {

    private final AgentContextSnapshotMapper mapper;
    private final AgentThreadMapper threadMapper;

    public MybatisAgentContextSnapshotStore(AgentContextSnapshotMapper mapper, AgentThreadMapper threadMapper) {
        this.mapper = mapper;
        this.threadMapper = threadMapper;
    }

    @Override
    public Optional<AgentContextSnapshotModel> findLatestSnapshot(String userId, String threadId) {
        AgentThreadEntity owned = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId).eq("USER_ID", userId));
        if (owned == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectLatest(threadId)).map(MybatisAgentContextSnapshotStore::toModel);
    }

    @Override
    public Optional<AgentContextSnapshotModel> findSnapshot(String userId, String threadId, String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            return Optional.empty();
        }
        AgentThreadEntity owned = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId).eq("USER_ID", userId));
        if (owned == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectBySnapshotId(threadId, snapshotId))
                .map(MybatisAgentContextSnapshotStore::toModel);
    }

    @Override
    @Transactional
    public void saveSnapshot(AgentContextSnapshotModel snapshot) {
        mapper.insert(toEntity(snapshot));
    }

    @Override
    @Transactional
    public boolean saveSnapshotIfCurrent(String userId, String threadId, String expectedLatestSnapshotId,
                                         AgentContextSnapshotModel snapshot) {
        if (snapshot == null || !threadId.equals(snapshot.threadId())) {
            return false;
        }
        AgentThreadEntity owned = threadMapper.selectForUpdate(threadId);
        if (owned == null || !userId.equals(owned.getUserId()) || !threadId.equals(owned.getThreadId())) {
            return false;
        }
        AgentContextSnapshotEntity current = mapper.selectLatest(threadId);
        String currentId = current == null ? null : current.getSnapshotId();
        if (!java.util.Objects.equals(currentId, expectedLatestSnapshotId)) {
            return false;
        }
        mapper.insert(toEntity(snapshot));
        return true;
    }

    private static AgentContextSnapshotEntity toEntity(AgentContextSnapshotModel model) {
        AgentContextSnapshotEntity entity = new AgentContextSnapshotEntity();
        entity.setSnapshotId(model.snapshotId());
        entity.setThreadId(model.threadId());
        entity.setThroughSequence(model.throughSequence());
        entity.setVersionNo(model.version());
        entity.setEstimatedTokens(model.estimatedTokens());
        entity.setSummary(model.summary());
        entity.setCreatedAt(model.createdAt());
        entity.setFormatVersion(model.formatVersion());
        entity.setBaseSnapshotId(model.baseSnapshotId());
        entity.setSourceFromSequence(model.sourceFromSequence());
        entity.setSourceEstimatedTokens(model.sourceEstimatedTokens());
        entity.setPromptVersion(model.promptVersion());
        entity.setSummaryMaxOutputTokens(model.summaryMaxOutputTokens());
        return entity;
    }

    private static AgentContextSnapshotModel toModel(AgentContextSnapshotEntity entity) {
        return new AgentContextSnapshotModel(entity.getSnapshotId(), entity.getThreadId(), value(entity.getThroughSequence()),
                value(entity.getVersionNo()), value(entity.getEstimatedTokens()), entity.getSummary(), entity.getCreatedAt(),
                intValue(entity.getFormatVersion(), 1), entity.getBaseSnapshotId(), value(entity.getSourceFromSequence()),
                intValue(entity.getSourceEstimatedTokens(), value(entity.getEstimatedTokens())),
                entity.getPromptVersion() == null ? "legacy" : entity.getPromptVersion(),
                intValue(entity.getSummaryMaxOutputTokens(), 0));
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static int intValue(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }
}
