package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentThreadService;
import cn.ethan.core.agent.thread.AgentTurnModel;
import cn.ethan.core.agent.thread.AgentTurnStore;
import cn.ethan.core.agent.thread.AgentThreadNotFoundException;

import java.util.ArrayList;
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
    private final AgentThreadService threads;

    public AgentExecutionTimelineService(AgentTurnStore turns, AgentThreadService threads) {
        this.turns = turns;
        this.threads = threads;
    }

    public AgentExecutionTimelineModel get(String userId, String turnId) {
        AgentTurnModel turn = turns.findTurn(userId, turnId)
                .orElseThrow(() -> new AgentThreadNotFoundException(turnId));
        List<AgentItemModel> all = new ArrayList<>();
        long cursor = 0L;
        for (;;) {
            List<AgentItemModel> page = threads.listItems(userId, turn.threadId(), cursor, 500);
            if (page.isEmpty()) break;
            all.addAll(page);
            cursor = page.get(page.size() - 1).sequence();
            if (page.size() < 500) break;
        }
        return new AgentExecutionTimelineModel(turn,
                all.stream()
                        .filter(item -> turnId.equals(item.turnId()))
                        .sorted(Comparator.comparingLong(AgentItemModel::sequence))
                        .toList());
    }
}
