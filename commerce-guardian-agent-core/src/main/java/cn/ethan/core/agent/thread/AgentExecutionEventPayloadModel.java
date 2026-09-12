package cn.ethan.core.agent.thread;

/**
 * 类型职责：记录一次上下文组装和预算观测事件，供运行诊断和恢复判断使用。
 *
 * @author ethan
 * @date 2026-09-13
 */
public record AgentExecutionEventPayloadModel(
        String eventKind,
        int estimatedTokens,
        int inputBudget,
        long snapshotThroughSequence,
        boolean compressed,
        boolean degraded,
        int droppedItems,
        long readWatermark,
        long coveredThroughSequence,
        int readItemCount,
        boolean historyComplete,
        int peakEstimatedTokens,
        int pressurePrunedToolResults
) implements AgentItemPayloadValue {

    public AgentExecutionEventPayloadModel {
        eventKind = required(eventKind, "eventKind");
        if (estimatedTokens < 0 || inputBudget < 0 || snapshotThroughSequence < 0
                || droppedItems < 0 || readWatermark < 0 || coveredThroughSequence < 0
                || readItemCount < 0 || peakEstimatedTokens < 0 || pressurePrunedToolResults < 0) {
            throw new IllegalArgumentException("执行事件计数不能为负数");
        }
    }

    @Override
    public AgentItemTypeEnum type() {
        return AgentItemTypeEnum.EXECUTION_EVENT;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }
}
