package cn.ethan.core.agent.thread;


import java.time.Instant;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;

/**
 * Agent Turn：一次输入到一次稳定状态的执行回合。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentTurnModel(
        String turnId,
        String threadId,
        String userId,
        String clientRequestId,
        String input,
        AgentTurnStatusEnum status,
        int queuePosition,
        String workflowRunId,
        String errorCode,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        AgentWorkflowAnswerInput workflowAnswerInput,
        long version,
        AgentTurnInputKindEnum inputKind,
        AgentOrderActionInput orderActionInput
) {
    public static final int MAX_CLIENT_REQUEST_ID_LENGTH = 128;
    public static final int MAX_USER_MESSAGE_LENGTH = 256;

    public AgentTurnModel(
            String turnId,
            String threadId,
            String userId,
            String clientRequestId,
            String input,
            AgentTurnStatusEnum status,
            int queuePosition,
            String workflowRunId,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, null, 0L);
    }

    public AgentTurnModel(
            String turnId,
            String threadId,
            String userId,
            String clientRequestId,
            String input,
            AgentTurnStatusEnum status,
            int queuePosition,
            String workflowRunId,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            AgentWorkflowAnswerInput workflowAnswerInput
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, workflowAnswerInput, 0L);
    }

    public AgentTurnModel(
            String turnId,
            String threadId,
            String userId,
            String clientRequestId,
            String input,
            AgentTurnStatusEnum status,
            int queuePosition,
            String workflowRunId,
            String errorCode,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            AgentWorkflowAnswerInput workflowAnswerInput,
            long version
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, workflowAnswerInput, version,
                workflowAnswerInput == null ? AgentTurnInputKindEnum.MESSAGE : AgentTurnInputKindEnum.WORKFLOW_ANSWER,
                null);
    }

    public AgentTurnModel {
        turnId = normalizeIdentity(turnId, "turnId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        threadId = normalizeIdentity(threadId, "threadId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        userId = normalizeIdentity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        clientRequestId = normalizeIdentity(clientRequestId, "clientRequestId", MAX_CLIENT_REQUEST_ID_LENGTH);
        input = input == null ? "" : input.trim();
        status = status == null ? AgentTurnStatusEnum.QUEUED : status;
        inputKind = inputKind == null
                ? workflowAnswerInput != null ? AgentTurnInputKindEnum.WORKFLOW_ANSWER
                : orderActionInput != null ? AgentTurnInputKindEnum.ORDER_ACTION : AgentTurnInputKindEnum.MESSAGE
                : inputKind;
        if (inputKind == AgentTurnInputKindEnum.WORKFLOW_ANSWER && workflowAnswerInput == null) {
            throw new IllegalArgumentException("Workflow answer Turn 缺少结构化输入");
        }
        if (inputKind == AgentTurnInputKindEnum.ORDER_ACTION && orderActionInput == null) {
            throw new IllegalArgumentException("订单动作 Turn 缺少结构化输入");
        }
        if (inputKind != AgentTurnInputKindEnum.ORDER_ACTION && orderActionInput != null) {
            throw new IllegalArgumentException("非订单动作 Turn 不能携带订单动作输入");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Turn version 不能为负数");
        }
        if (workflowAnswerInput != null && !workflowAnswerInput.runId().equals(workflowRunId)) {
            throw new IllegalArgumentException("回答 Turn 的 workflowRunId 与结构化输入不一致");
        }
    }

    private static String normalizeIdentity(String value, String name, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " 不能为空且长度不能超过 " + maxLength);
        }
        return normalized;
    }

    public AgentTurnModel queued(int position) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.QUEUED, position, workflowRunId, errorCode,
                createdAt, startedAt, finishedAt, workflowAnswerInput, version + 1,
                inputKind, orderActionInput);
    }

    public AgentTurnModel active(Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.ACTIVE, queuePosition, workflowRunId, errorCode,
                createdAt, at, null, workflowAnswerInput, version + 1,
                inputKind, orderActionInput);
    }

    public AgentTurnModel terminal(AgentTurnStatusEnum terminal, String code, Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                terminal, queuePosition, workflowRunId, code, createdAt, startedAt, at,
                workflowAnswerInput, version + 1, inputKind, orderActionInput);
    }

    public AgentTurnModel workflow(String runId, AgentTurnStatusEnum nextStatus) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                nextStatus, queuePosition, runId, errorCode, createdAt, startedAt, finishedAt,
                workflowAnswerInput, version + 1, inputKind, orderActionInput);
    }
}
