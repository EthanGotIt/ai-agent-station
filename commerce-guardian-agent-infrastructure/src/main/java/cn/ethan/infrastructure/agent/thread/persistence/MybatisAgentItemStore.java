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
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public final class MybatisAgentItemStore implements AgentItemStore {

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
        long sequence = thread.getNextSequence() == null || thread.getNextSequence() < 1
                ? 1L : thread.getNextSequence();
        AgentItemEntity entity = new AgentItemEntity();
        entity.setItemId(item.itemId());
        entity.setThreadId(item.threadId());
        entity.setTurnId(item.turnId());
        entity.setSequenceNo(sequence);
        entity.setItemType(item.type().name());
        entity.setPayload(item.payload());
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
        return itemMapper.selectAfter(threadId, Math.max(0L, afterSequence), Math.max(1, Math.min(limit, 500)))
                .stream().map(MybatisAgentItemStore::toModel).toList();
    }

    private static AgentItemModel toModel(AgentItemEntity entity) {
        return new AgentItemModel(entity.getItemId(), entity.getThreadId(), entity.getTurnId(), value(entity.getSequenceNo()),
                AgentItemTypeEnum.valueOf(entity.getItemType()), entity.getPayload(), entity.getCreatedAt());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
