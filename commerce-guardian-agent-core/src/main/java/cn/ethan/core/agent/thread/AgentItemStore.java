package cn.ethan.core.agent.thread;

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
     * 捕获本次上下文读取的已提交水位。水位只界定读取范围，不表示事实已经被摘要覆盖。
     *
     * <p>没有专用索引的适配器使用游标分页兜底；数据库适配器应覆盖该方法，直接读取 Thread 的最大序号。</p>
     */
    default long captureWatermark(String userId, String threadId) {
        long cursor = 0L;
        long watermark = 0L;
        while (true) {
            List<AgentItemModel> page = listItems(userId, threadId, cursor, 300);
            if (page == null || page.isEmpty()) {
                return watermark;
            }
            if (page.size() > 300) {
                throw new IllegalStateException("Item 分页超过固定大小");
            }
            long nextCursor = cursor;
            for (AgentItemModel item : page) {
                if (item == null || item.sequence() <= nextCursor) {
                    throw new IllegalStateException("Item Sequence 未严格前进");
                }
                nextCursor = item.sequence();
                watermark = Math.max(watermark, item.sequence());
            }
            if (nextCursor <= cursor) {
                return watermark;
            }
            cursor = nextCursor;
        }
    }

    /**
     * 在固定水位内读取 Item。默认实现兼容只有游标读取能力的适配器，并丢弃水位之后的记录。
     */
    default List<AgentItemModel> listItemsThrough(
            String userId, String threadId, long afterSequence, long throughSequence, int limit
    ) {
        if (throughSequence <= Math.max(0L, afterSequence)) {
            return List.of();
        }
        List<AgentItemModel> page = listItems(userId, threadId, afterSequence, Math.max(1, Math.min(limit, 300)));
        if (page == null || page.isEmpty()) {
            return List.of();
        }
        return page.stream()
                // 保留游标之前和空值，让组装器显式拒绝重复/无效页面；只隔离固定水位之后的新事实。
                .filter(item -> item == null || item.sequence() <= throughSequence)
                .toList();
    }

}
