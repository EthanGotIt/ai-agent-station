package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.event.AgentThreadEventGateway;
import cn.ethan.core.agent.thread.AgentItemJournal;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 类型职责：集中承接 Item Store 的序号分配、幂等追加和提交后事件发布。
 *
 * <p>事件只在本地事务成功提交后发布；事件总线故障不会回滚已提交事实，客户端可通过
 * 游标回放补偿。该边界为后续迁移各业务调用方提供单一入口。</p>
 *
 * @author ethan
 * @date 2026-09-13
 */
@Component
public final class TransactionalAgentItemJournal implements AgentItemJournal {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionalAgentItemJournal.class);

    private final AgentItemStore items;
    private final AgentThreadEventGateway events;

    public TransactionalAgentItemJournal(AgentItemStore items, AgentThreadEventGateway events) {
        this.items = items;
        this.events = events;
    }

    @Override
    public AgentItemModel append(AgentItemModel item) {
        long sequence = items.appendItem(item);
        AgentItemModel persisted = withSequence(item, sequence);
        publish(persisted);
        return persisted;
    }

    @Override
    public AgentItemStore.AppendResult appendIfAbsent(AgentItemModel item) {
        AgentItemStore.AppendResult result = items.appendItemIfAbsent(item);
        if (result.inserted()) {
            publish(result.item());
        }
        return result;
    }

    @Override
    public void publish(AgentItemModel persistedItem) {
        if (persistedItem != null) {
            publishAfterCommit(persistedItem);
        }
    }

    private void publishAfterCommit(AgentItemModel item) {
        if (events == null) {
            return;
        }
        Runnable publish = () -> {
            try {
                events.itemCreated(item);
            } catch (RuntimeException failure) {
                // 事实已经提交；实时发布失败由 SSE 的游标回放补偿，不能反向改写事实。
                LOGGER.warn("Item event publish failed, itemId={}, threadId={}, errorType={}",
                        item.itemId(), item.threadId(), failure.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    private static AgentItemModel withSequence(AgentItemModel item, long sequence) {
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt());
    }
}
