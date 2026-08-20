package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentThreadModel;
import cn.ethan.core.agent.thread.AgentThreadStatusEnum;
import cn.ethan.core.agent.thread.AgentThreadStore;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：只持久化 Thread 元数据，不承载 Turn、Item 或 Workflow 状态。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public final class MybatisAgentThreadStore implements AgentThreadStore {

    private final AgentThreadMapper mapper;

    public MybatisAgentThreadStore(AgentThreadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void createThread(AgentThreadModel thread) {
        mapper.insert(toEntity(thread));
    }

    @Override
    public Optional<AgentThreadModel> findThread(String userId, String threadId) {
        AgentThreadEntity entity = mapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId)
                .eq("USER_ID", userId));
        return Optional.ofNullable(entity).map(MybatisAgentThreadStore::toModel);
    }

    @Override
    public List<AgentThreadModel> listThreads(String userId) {
        return mapper.selectByUser(userId).stream().map(MybatisAgentThreadStore::toModel).toList();
    }

    @Override
    public List<AgentThreadModel> listThreads(String userId, int offset, int limit) {
        return mapper.selectPage(userId, Math.max(0, offset), Math.max(1, limit)).stream()
                .map(MybatisAgentThreadStore::toModel)
                .toList();
    }

    @Override
    public long countThreads(String userId) {
        return mapper.countByUser(userId);
    }

    @Override
    public void updateThread(AgentThreadModel thread) {
        mapper.update(toEntity(thread), new UpdateWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", thread.threadId())
                .eq("USER_ID", thread.userId()));
    }

    private static AgentThreadEntity toEntity(AgentThreadModel model) {
        AgentThreadEntity entity = new AgentThreadEntity();
        entity.setThreadId(model.threadId());
        entity.setUserId(model.userId());
        entity.setTitle(model.title());
        entity.setStatus(model.status().name());
        entity.setContextType(model.contextType());
        entity.setContextId(model.contextId());
        entity.setNextSequence(model.nextSequence());
        entity.setCreatedAt(model.createdAt());
        entity.setUpdatedAt(model.updatedAt());
        return entity;
    }

    private static AgentThreadModel toModel(AgentThreadEntity entity) {
        return new AgentThreadModel(entity.getThreadId(), entity.getUserId(), entity.getTitle(),
                AgentThreadStatusEnum.valueOf(entity.getStatus()), entity.getContextType(), entity.getContextId(),
                value(entity.getNextSequence()), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
