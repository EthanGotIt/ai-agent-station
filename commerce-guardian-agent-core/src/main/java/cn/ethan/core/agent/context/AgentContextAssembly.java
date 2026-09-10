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
        String summary,
        AgentModelContext modelContext,
        AgentContextBudgetReport report
) {

    public AgentContextAssembly {
        items = items == null ? List.of() : List.copyOf(items);
        summary = summary == null ? "" : summary;
        modelContext = modelContext == null
                ? new AgentModelContext(summary, items,
                report == null ? 0L : report.readWatermark(),
                report == null ? 0L : report.snapshotThroughSequence(),
                report == null ? 0L : report.coveredThroughSequence(),
                summary.isBlank() ? "raw" : "compressed")
                : modelContext;
        report = report == null ? new AgentContextBudgetReport(0, 0, 0, false, false) : report;
    }

    public AgentContextAssembly(List<AgentItemModel> items, AgentContextBudgetReport report) {
        this(items, "", null, report);
    }
}
