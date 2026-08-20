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
}
