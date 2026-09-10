package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 类型职责：追加和游标读取 Thread Item，并通过 Thread 行锁分配单调序号。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public class MybatisAgentItemStore implements AgentItemStore {

    private final AgentItemMapper itemMapper;
    private final AgentThreadMapper threadMapper;

    public MybatisAgentItemStore(AgentItemMapper itemMapper, AgentThreadMapper threadMapper) {
        this.itemMapper = itemMapper;
        this.threadMapper = threadMapper;
    }

    @Override
    @Transactional
    public long appendItem(AgentItemModel item) {
        AgentThreadEntity thread = threadMapper.selectForUpdate(item.threadId());
        if (thread == null) {
            throw new IllegalStateException("Thread 不存在：" + item.threadId());
        }
        return appendLocked(item, thread);
    }

    /**
     * 以 Thread 行锁串行化幂等 Item 检查和 Sequence 分配，避免并发重放产生第二条超限事实。
     */
    @Override
    @Transactional
    public AppendResult appendItemIfAbsent(AgentItemModel item) {
        AgentThreadEntity thread = threadMapper.selectForUpdate(item.threadId());
        if (thread == null) {
            throw new IllegalStateException("Thread 不存在：" + item.threadId());
        }
        AgentItemEntity existing = itemMapper.selectById(item.itemId());
        if (existing != null) {
            verifySameFact(item, existing);
            return new AppendResult(toModel(existing), false);
        }
        long sequence = appendLocked(item, thread);
        return new AppendResult(withSequence(item, sequence), true);
    }

    private long appendLocked(AgentItemModel item, AgentThreadEntity thread) {
        long sequence = thread.getNextSequence() == null || thread.getNextSequence() < 1
                ? 1L : thread.getNextSequence();
        AgentItemEntity entity = new AgentItemEntity();
        entity.setItemId(item.itemId());
        entity.setThreadId(item.threadId());
        entity.setTurnId(item.turnId());
        entity.setSequenceNo(sequence);
        entity.setItemType(item.type().name());
        entity.setPayloadJson(item.payloadJson());
        entity.setCreatedAt(item.createdAt());
        itemMapper.insert(entity);
        thread.setNextSequence(sequence + 1);
        thread.setUpdatedAt(item.createdAt());
        threadMapper.updateById(thread);
        return sequence;
    }

    @Override
    public List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit) {
        AgentThreadEntity owned = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId).eq("USER_ID", userId));
        if (owned == null) {
            return List.of();
        }
        return itemMapper.selectAfter(threadId, Math.max(0L, afterSequence), Math.max(1, Math.min(limit, 501)))
                .stream().map(MybatisAgentItemStore::toModel).toList();
    }

    @Override
    public List<AgentItemModel> listTurnItems(
            String userId, String threadId, String turnId, long afterSequence, int limit
    ) {
        AgentThreadEntity owned = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId).eq("USER_ID", userId));
        if (owned == null) {
            return List.of();
        }
        return itemMapper.selectTurnAfter(threadId, turnId, Math.max(0L, afterSequence),
                        Math.max(1, Math.min(limit, 500)))
                .stream().map(MybatisAgentItemStore::toModel).toList();
    }

    @Override
    public long captureWatermark(String userId, String threadId) {
        AgentThreadEntity owned = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId).eq("USER_ID", userId));
        if (owned == null) {
            return 0L;
        }
        Long value = itemMapper.selectMaxSequence(threadId);
        return value == null ? 0L : Math.max(0L, value);
    }

    @Override
    public List<AgentItemModel> listItemsThrough(
            String userId, String threadId, long afterSequence, long throughSequence, int limit
    ) {
        AgentThreadEntity owned = threadMapper.selectOne(new QueryWrapper<AgentThreadEntity>()
                .eq("THREAD_ID", threadId).eq("USER_ID", userId));
        if (owned == null || throughSequence <= Math.max(0L, afterSequence)) {
            return List.of();
        }
        return itemMapper.selectThrough(threadId, Math.max(0L, afterSequence), throughSequence,
                        Math.max(1, Math.min(limit, 300)))
                .stream()
                .map(MybatisAgentItemStore::toModel)
                .toList();
    }

    private static AgentItemModel toModel(AgentItemEntity entity) {
        return new AgentItemModel(entity.getItemId(), entity.getThreadId(), entity.getTurnId(), value(entity.getSequenceNo()),
                AgentItemTypeEnum.valueOf(entity.getItemType()), entity.getPayloadJson(), entity.getCreatedAt());
    }

    private static AgentItemModel withSequence(AgentItemModel item, long sequence) {
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt());
    }

    private static void verifySameFact(AgentItemModel requested, AgentItemEntity existing) {
        if (!requested.itemId().equals(existing.getItemId())
                || !requested.threadId().equals(existing.getThreadId())
                || !java.util.Objects.equals(requested.turnId(), existing.getTurnId())
                || !requested.type().name().equals(existing.getItemType())
                || !requested.payload().equals(existing.getPayloadJson())) {
            throw new IllegalStateException("幂等 ItemId 已绑定到不同事实：" + requested.itemId());
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
