package cn.ethan.core.agent.thread;


import cn.ethan.core.agent.coordination.AgentContinuationInput;
import cn.ethan.core.agent.coordination.AgentOrderActionInput;

import java.time.Instant;

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
        AgentQuestionAnswerInput questionAnswerInput,
        long version,
        AgentTurnInputKindEnum inputKind,
        AgentOrderActionInput orderActionInput,
        AgentContinuationInput continuationInput,
        AgentWorkflowDecisionInput workflowDecisionInput
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
                errorCode, createdAt, startedAt, finishedAt, null, null, 0L, null, null, null, null);
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
                errorCode, createdAt, startedAt, finishedAt, workflowAnswerInput, null, version,
                workflowAnswerInput == null ? AgentTurnInputKindEnum.MESSAGE : AgentTurnInputKindEnum.WORKFLOW_ANSWER,
                null, null, null);
    }

    /** 创建 QuestionCard 回答 Turn；QuestionCard 可恢复到 Agent 或固定 Workflow。 */
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
            AgentQuestionAnswerInput questionAnswerInput
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, null, questionAnswerInput, 0L,
                AgentTurnInputKindEnum.QUESTION_ANSWER, null, null, null);
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
            long version,
            AgentTurnInputKindEnum inputKind,
            AgentOrderActionInput orderActionInput
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId,
                errorCode, createdAt, startedAt, finishedAt, workflowAnswerInput, null, version,
                inputKind, orderActionInput, null, null);
    }

    /** 保留既有输入构造边界；新决策 Turn 应显式携带 workflowDecisionInput。 */
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
            long version,
            AgentTurnInputKindEnum inputKind,
            AgentOrderActionInput orderActionInput,
            AgentContinuationInput continuationInput,
            AgentWorkflowDecisionInput workflowDecisionInput
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId, errorCode,
                createdAt, startedAt, finishedAt, workflowAnswerInput, null, version, inputKind, orderActionInput,
                continuationInput, workflowDecisionInput);
    }

    /** 保留既有输入构造边界；新决策 Turn 应显式携带 workflowDecisionInput。 */
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
            long version,
            AgentTurnInputKindEnum inputKind,
            AgentOrderActionInput orderActionInput,
            AgentContinuationInput continuationInput
    ) {
        this(turnId, threadId, userId, clientRequestId, input, status, queuePosition, workflowRunId, errorCode,
                createdAt, startedAt, finishedAt, workflowAnswerInput, null, version, inputKind, orderActionInput,
                continuationInput, null);
    }

    public AgentTurnModel {
        turnId = normalizeIdentity(turnId, "turnId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        threadId = normalizeIdentity(threadId, "threadId", AgentThreadModel.MAX_THREAD_ID_LENGTH);
        userId = normalizeIdentity(userId, "userId", AgentThreadModel.MAX_USER_ID_LENGTH);
        clientRequestId = normalizeIdentity(clientRequestId, "clientRequestId", MAX_CLIENT_REQUEST_ID_LENGTH);
        input = input == null ? "" : input.trim();
        status = status == null ? AgentTurnStatusEnum.QUEUED : status;
        inputKind = inputKind == null
                ? workflowDecisionInput != null ? AgentTurnInputKindEnum.WORKFLOW_DECISION
                : continuationInput != null ? AgentTurnInputKindEnum.AGENT_CONTINUATION
                : questionAnswerInput != null ? AgentTurnInputKindEnum.QUESTION_ANSWER
                : workflowAnswerInput != null ? AgentTurnInputKindEnum.WORKFLOW_ANSWER
                : orderActionInput != null ? AgentTurnInputKindEnum.ORDER_ACTION : AgentTurnInputKindEnum.MESSAGE
                : inputKind;
        if (inputKind == AgentTurnInputKindEnum.WORKFLOW_ANSWER && workflowAnswerInput == null) {
            throw new IllegalArgumentException("Workflow answer Turn 缺少结构化输入");
        }
        if (inputKind == AgentTurnInputKindEnum.QUESTION_ANSWER && questionAnswerInput == null) {
            throw new IllegalArgumentException("QuestionCard answer Turn 缺少结构化输入");
        }
        if (inputKind == AgentTurnInputKindEnum.ORDER_ACTION && orderActionInput == null) {
            throw new IllegalArgumentException("订单动作 Turn 缺少结构化输入");
        }
        if (inputKind == AgentTurnInputKindEnum.AGENT_CONTINUATION && continuationInput == null) {
            throw new IllegalArgumentException("Agent 续跑 Turn 缺少结构化输入");
        }
        if (inputKind == AgentTurnInputKindEnum.WORKFLOW_DECISION && workflowDecisionInput == null) {
            throw new IllegalArgumentException("Workflow decision Turn 缺少结构化输入");
        }
        if (inputKind != AgentTurnInputKindEnum.ORDER_ACTION && orderActionInput != null) {
            throw new IllegalArgumentException("非订单动作 Turn 不能携带订单动作输入");
        }
        if (inputKind != AgentTurnInputKindEnum.AGENT_CONTINUATION && continuationInput != null) {
            throw new IllegalArgumentException("非 Agent 续跑 Turn 不能携带续跑输入");
        }
        if (inputKind != AgentTurnInputKindEnum.WORKFLOW_DECISION && workflowDecisionInput != null) {
            throw new IllegalArgumentException("非 Workflow decision Turn 不能携带决策输入");
        }
        if (inputKind != AgentTurnInputKindEnum.QUESTION_ANSWER && questionAnswerInput != null) {
            throw new IllegalArgumentException("非 QuestionCard answer Turn 不能携带回答输入");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Turn version 不能为负数");
        }
        if (workflowAnswerInput != null && !workflowAnswerInput.runId().equals(workflowRunId)) {
            throw new IllegalArgumentException("回答 Turn 的 workflowRunId 与结构化输入不一致");
        }
        if (workflowDecisionInput != null && !workflowDecisionInput.runId().equals(workflowRunId)) {
            throw new IllegalArgumentException("决策 Turn 的 workflowRunId 与结构化输入不一致");
        }
        if (questionAnswerInput != null
                && questionAnswerInput.resumeTarget() == cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum.WORKFLOW
                && !java.util.Objects.equals(questionAnswerInput.runId(), workflowRunId)) {
            throw new IllegalArgumentException("QuestionCard 回答 Turn 的 workflowRunId 与结构化输入不一致");
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
                createdAt, startedAt, finishedAt, workflowAnswerInput, questionAnswerInput, version + 1,
                inputKind, orderActionInput, continuationInput, workflowDecisionInput);
    }

    public AgentTurnModel active(Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                AgentTurnStatusEnum.ACTIVE, queuePosition, workflowRunId, errorCode,
                createdAt, at, null, workflowAnswerInput, questionAnswerInput, version + 1,
                inputKind, orderActionInput, continuationInput, workflowDecisionInput);
    }

    public AgentTurnModel terminal(AgentTurnStatusEnum terminal, String code, Instant at) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                terminal, queuePosition, workflowRunId, code, createdAt, startedAt, at,
                workflowAnswerInput, questionAnswerInput, version + 1, inputKind, orderActionInput, continuationInput,
                workflowDecisionInput);
    }

    public AgentTurnModel workflow(String runId, AgentTurnStatusEnum nextStatus) {
        return new AgentTurnModel(turnId, threadId, userId, clientRequestId, input,
                nextStatus, queuePosition, runId, errorCode, createdAt, startedAt, finishedAt,
                workflowAnswerInput, questionAnswerInput, version + 1, inputKind, orderActionInput, continuationInput,
                workflowDecisionInput);
    }
}
