package cn.ethan.core.agent.execution;

import java.time.Instant;
import java.util.List;

/**
 * 类型职责：持久化同一 Turn 暂停恢复所需的受控执行位置，不保存 Thinking 或运行时调用栈。
 *
 * @author ethan
 * @date 2026-09-29
 */
public record AgentTurnExecutionStateModel(
        String turnId,
        long activeDurationMillis,
        String activeToolBatchId,
        int nextToolIndex,
        List<ToolCall> toolCalls,
        long version,
        Instant updatedAt
) {
    public static final int MAX_TOOL_CALLS_PER_BATCH = 32;
    public static final int MAX_TOOL_ARGUMENTS_LENGTH = 32_768;

    public AgentTurnExecutionStateModel {
        turnId = require(turnId, "turnId", 64);
        if (activeDurationMillis < 0 || nextToolIndex < 0 || version < 0) {
            throw new IllegalArgumentException("Turn 执行位置和累计时长不能为负数");
        }
        activeToolBatchId = activeToolBatchId == null || activeToolBatchId.isBlank()
                ? null : require(activeToolBatchId, "activeToolBatchId", 64);
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (toolCalls.size() > MAX_TOOL_CALLS_PER_BATCH || nextToolIndex > toolCalls.size()) {
            throw new IllegalArgumentException("Turn 工具批次位置超出受控范围");
        }
        if (!toolCalls.isEmpty() && activeToolBatchId == null) {
            throw new IllegalArgumentException("工具调用快照必须关联工具批次");
        }
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }

    public AgentTurnExecutionStateModel next(
            long nextActiveDurationMillis,
            String nextBatchId,
            int nextIndex,
            List<ToolCall> nextCalls,
            Instant at
    ) {
        return new AgentTurnExecutionStateModel(turnId, nextActiveDurationMillis, nextBatchId,
                nextIndex, nextCalls, version + 1, at);
    }

    private static String require(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    /** 一个供应商工具调用及其受控回执 Item 引用；状态恢复时不得再次执行已完成调用。 */
    public record ToolCall(
            String providerCallId,
            String invocationId,
            String toolName,
            String argumentsJson,
            ToolCallStatusEnum status,
            String resultItemId
    ) {
        public ToolCall {
            providerCallId = require(providerCallId, "providerCallId", 128);
            invocationId = require(invocationId, "invocationId", 64);
            toolName = require(toolName, "toolName", 128);
            argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            if (argumentsJson.length() > MAX_TOOL_ARGUMENTS_LENGTH) {
                throw new IllegalArgumentException("工具调用参数超过恢复快照上限");
            }
            status = status == null ? ToolCallStatusEnum.PENDING : status;
            resultItemId = resultItemId == null || resultItemId.isBlank()
                    ? null : require(resultItemId, "resultItemId", 64);
            if (status == ToolCallStatusEnum.COMPLETED && resultItemId == null) {
                throw new IllegalArgumentException("已完成工具调用必须引用持久化结果 Item");
            }
        }

        public ToolCall completed(String itemId) {
            return new ToolCall(providerCallId, invocationId, toolName, argumentsJson,
                    ToolCallStatusEnum.COMPLETED, itemId);
        }
    }

    public enum ToolCallStatusEnum {
        PENDING,
        COMPLETED,
        SKIPPED
    }
}
