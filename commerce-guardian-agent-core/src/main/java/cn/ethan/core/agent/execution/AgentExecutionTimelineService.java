package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemStore;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentThreadNotFoundException;

import java.util.Comparator;
import java.util.List;

/**
 * 类型职责：只读投影 Thread/Turn/Item 事实，不触发模型调用、Workflow 或外部动作。
 *
 * @author ethan
 * @date 2026-08-20
 */
public final class AgentExecutionTimelineService {

    private final AgentTurnStore turns;
    private final AgentItemStore items;

    public AgentExecutionTimelineService(AgentTurnStore turns, AgentItemStore items) {
        this.turns = turns;
        this.items = items;
    }

    public AgentExecutionTimelineModel get(String userId, String turnId) {
        AgentTurnModel turn = turns.findTurn(userId, turnId)
                .orElseThrow(() -> new AgentThreadNotFoundException(turnId));
        List<AgentItemModel> all = new java.util.ArrayList<>();
        long cursor = 0L;
        for (;;) {
            List<AgentItemModel> page = items.listTurnItems(userId, turn.threadId(), turnId, cursor, 500);
            if (page == null || page.isEmpty()) {
                break;
            }
            long next = cursor;
            for (AgentItemModel item : page) {
                if (item == null || item.sequence() <= next || !turnId.equals(item.turnId())) {
                    next = -1L;
                    break;
                }
                all.add(item);
                next = item.sequence();
            }
            // 持久化适配器应按游标推进；对重复、乱序或无效页做防御，避免坏页永久循环。
            if (next <= cursor) {
                break;
            }
            cursor = next;
            if (page.size() < 500) {
                break;
            }
        }
        return new AgentExecutionTimelineModel(turn,
                all.stream()
                        .filter(item -> turnId.equals(item.turnId()))
                        .sorted(Comparator.comparingLong(AgentItemModel::sequence))
                        .toList());
    }
}
