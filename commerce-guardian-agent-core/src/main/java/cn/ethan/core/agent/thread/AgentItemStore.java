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
}
