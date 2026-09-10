package cn.ethan.core.agent.thread;

import java.util.ArrayList;
import java.util.List;

/**
 * 类型职责：追加和游标读取 Thread Item 事实，保证序号由持久化边界分配。
 *
 * @author ethan
 * @date 2026-08-20
 */
public interface AgentItemStore {

    long appendItem(AgentItemModel item);

    /**
     * 以 ItemId 作为幂等键追加事实；重复调用必须返回已经持久化的 Item，不能分配第二个 Sequence。
     *
     * <p>默认实现保留简单内存适配器的兼容性；需要跨事务幂等的数据库适配器应覆盖该方法。</p>
     */
    default AppendResult appendItemIfAbsent(AgentItemModel item) {
        long sequence = appendItem(item);
        return new AppendResult(
                new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                        item.type(), item.payload(), item.createdAt()), true);
    }

    List<AgentItemModel> listItems(String userId, String threadId, long afterSequence, int limit);

    /**
     * 读取游标之后的最新 Item 窗口，结果按 Sequence 升序返回。
     *
     * <p>默认实现用于没有倒序查询能力的适配器，按已有游标端口分页并只保留末尾窗口；数据库适配器应覆盖该方法，
     * 直接使用 Thread/Sequence 索引，避免长历史导致每次组装从最早 Item 开始扫描。</p>
     */
    default List<AgentItemModel> listLatestItems(String userId, String threadId, long afterSequence, int limit) {
        int requested = Math.max(1, Math.min(limit, 501));
        List<AgentItemModel> latest = new ArrayList<>(requested);
        long cursor = Math.max(0L, afterSequence);
        while (true) {
            List<AgentItemModel> page = listItems(userId, threadId, cursor, 501);
            if (page == null || page.isEmpty()) {
                break;
            }
            long nextCursor = cursor;
            for (AgentItemModel item : page) {
                if (item == null || item.sequence() <= cursor) {
                    continue;
                }
                nextCursor = Math.max(nextCursor, item.sequence());
                latest.add(item);
                if (latest.size() > requested) {
                    latest.remove(0);
                }
            }
            if (nextCursor <= cursor || page.size() < 501) {
                break;
            }
            cursor = nextCursor;
        }
        return List.copyOf(latest);
    }

    /** 幂等追加的结果，inserted=false 表示本次只复用了已有事实。 */
    record AppendResult(AgentItemModel item, boolean inserted) {

        public AppendResult {
            if (item == null) {
                throw new IllegalArgumentException("幂等追加结果必须包含 Item");
            }
        }
    }
}
