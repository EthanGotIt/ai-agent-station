package cn.ethan.core.agent.thread;

/**
 * 类型职责：定义 Item 事实追加和幂等重放的统一日志边界。
 *
 * <p>实现负责在持久化序号分配完成后发布实时事件；Core 不感知数据库或消息总线，
 * 现有 {@link AgentItemStore} 仍作为底层事实存储端口。</p>
 *
 * @author ethan
 * @date 2026-09-13
 */
public interface AgentItemJournal {

    /** 追加一条事实并返回带持久化 Sequence 的 Item。 */
    AgentItemModel append(AgentItemModel item);

    /** 以 ItemId 幂等追加；重复事实不得再次分配 Sequence 或发布事件。 */
    AgentItemStore.AppendResult appendIfAbsent(AgentItemModel item);

    /**
     * 为已经在同一事务内原子落库的首个 Item 补发提交后事件，不再次写入事实。
     * 默认实现保持旧 Store/测试适配器兼容。
     */
    default void publish(AgentItemModel persistedItem) {
        // 旧适配器没有实时事件能力时保持兼容；游标回放仍可恢复该事实。
    }
}
