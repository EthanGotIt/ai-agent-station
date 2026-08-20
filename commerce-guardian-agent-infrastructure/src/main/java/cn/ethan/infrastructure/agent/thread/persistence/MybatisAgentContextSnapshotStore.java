package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.context.AgentContextSnapshotModel;
import cn.ethan.core.agent.context.AgentContextSnapshotStore;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;

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
    public void saveSnapshot(AgentContextSnapshotModel snapshot) {
        mapper.insert(toEntity(snapshot));
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
        return entity;
    }

    private static AgentContextSnapshotModel toModel(AgentContextSnapshotEntity entity) {
        return new AgentContextSnapshotModel(entity.getSnapshotId(), entity.getThreadId(), value(entity.getThroughSequence()),
                value(entity.getVersionNo()), value(entity.getEstimatedTokens()), entity.getSummary(), entity.getCreatedAt());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }
}
