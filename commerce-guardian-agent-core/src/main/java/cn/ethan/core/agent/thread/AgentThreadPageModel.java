package cn.ethan.core.agent.thread;

import java.util.List;

/**
 * 类型职责：表达按数据库分页读取的 Thread 元数据，避免应用层加载全部记录。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentThreadPageModel(
        List<AgentThreadModel> items,
        int page,
        int size,
        long total
) {

    public AgentThreadPageModel {
        items = items == null ? List.of() : List.copyOf(items);
        page = Math.max(0, page);
        size = Math.max(1, size);
        total = Math.max(0L, total);
    }
}
