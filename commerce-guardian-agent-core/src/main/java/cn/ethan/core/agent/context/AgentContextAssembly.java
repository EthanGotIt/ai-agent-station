package cn.ethan.core.agent.context;

import cn.ethan.core.agent.thread.AgentItemModel;

import java.util.List;

/**
 * 类型职责：将模型输入 Item 与预算报告绑定，避免调用方丢失上下文工程事实。
 *
 * @author ethan
 * @date 2026-08-20
 */
public record AgentContextAssembly(
        List<AgentItemModel> items,
        AgentContextBudgetReport report
) {

    public AgentContextAssembly {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
