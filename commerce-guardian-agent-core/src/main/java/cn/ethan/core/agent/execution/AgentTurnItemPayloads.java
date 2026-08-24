package cn.ethan.core.agent.execution;

import cn.ethan.core.agent.context.AgentContextBudgetReport;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;
import cn.ethan.core.agent.thread.AgentItemModel;
import cn.ethan.core.agent.thread.AgentItemTypeEnum;
import cn.ethan.core.agent.thread.AgentTurnStatusEnum;

/**
 * 类型职责：生成 Runtime 写入的受控 Item payload，并保持序号回填不带业务副作用。
 *
 * @author ethan
 * @date 2026-08-24
 */
public final class AgentTurnItemPayloads {

    private AgentTurnItemPayloads() {
    }

    public static String orderAction(AgentOrderActionInput action) {
        return "{\"sourceTurnId\":\"" + escape(action.sourceTurnId())
                + "\",\"orderId\":\"" + escape(action.orderId())
                + "\",\"actionType\":\"" + action.actionType().name() + "\"}";
    }

    public static String turnState(AgentTurnStatusEnum status, String errorCode) {
        return "{\"status\":\"" + status.name() + "\",\"errorCode\":"
                + (errorCode == null ? "null" : "\"" + escape(errorCode) + "\"") + "}";
    }

    public static String context(AgentContextBudgetReport report) {
        return "{\"kind\":\"CONTEXT_ASSEMBLED\",\"estimatedTokens\":" + report.estimatedTokens()
                + ",\"inputBudget\":" + report.inputBudget()
                + ",\"snapshotThroughSequence\":" + report.snapshotThroughSequence()
                + ",\"compressed\":" + report.compressed()
                + ",\"degraded\":" + report.degraded() + "}";
    }

    public static AgentItemModel withSequence(AgentItemModel item, long sequence) {
        return new AgentItemModel(item.itemId(), item.threadId(), item.turnId(), sequence,
                item.type(), item.payload(), item.createdAt());
    }

    public static AgentItemTypeEnum parseType(String value) {
        try {
            return AgentItemTypeEnum.valueOf(value);
        } catch (RuntimeException failure) {
            return AgentItemTypeEnum.EXECUTION_EVENT;
        }
    }

    public static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
