package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentTurnModel;

import java.util.List;

/**
 * 类型职责：表达从持久化 Item 重建的单 Turn 执行时间线，读取不会重新执行模型或副作用。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentExecutionTimelineModel(
        AgentTurnModel turn,
        List<AgentItemModel> items
) {

    public AgentExecutionTimelineModel {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
