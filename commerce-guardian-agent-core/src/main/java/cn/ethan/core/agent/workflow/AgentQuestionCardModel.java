package cn.ethan.core.agent.workflow;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 类型职责：保存由 Agent 或 Workflow 发起的受控问题，并声明回答后的恢复目标。
 *
 * <p>QuestionCard 只用于询问用户，不表达外部写操作授权；固定 Workflow 的执行确认
 * 必须使用 {@link AgentWorkflowCheckpointModel}。</p>
 *
 * @author ethan
 * @date 2026-08-27
 */
public record AgentQuestionCardModel(
        String questionId,
        String runId,
        String threadId,
        String turnId,
        String userId,
        AgentQuestionCardResumeTargetEnum resumeTarget,
        int stepNo,
        long version,
        String title,
        String prompt,
        String fieldsJson,
        AgentQuestionCardStatusEnum status,
        Instant createdAt,
        Instant answeredAt,
        String answerTurnId,
        AgentQuestionCardAnswerEnqueueStatusEnum answerEnqueueStatus,
        List<AgentWorkflowQuestionFieldModel> answerFields
) {

    public AgentQuestionCardModel {
        questionId = identity(questionId, "questionId");
        threadId = identity(threadId, "threadId");
        turnId = identity(turnId, "turnId");
        userId = identity(userId, "userId");
        if (runId != null && runId.isBlank()) {
            runId = null;
        } else if (runId != null) {
            runId = runId.trim();
        }
        resumeTarget = resumeTarget == null ? AgentQuestionCardResumeTargetEnum.AGENT : resumeTarget;
        if (resumeTarget == AgentQuestionCardResumeTargetEnum.WORKFLOW && runId == null) {
            throw new IllegalArgumentException("Workflow QuestionCard 必须绑定 runId");
        }
        if (stepNo < 0 || version < 0 || createdAt == null) {
            throw new IllegalArgumentException("QuestionCard version、stepNo 和 createdAt 必须有效");
        }
        title = title == null || title.isBlank() ? "需要补充信息" : title.trim();
        prompt = prompt == null ? "" : prompt.trim();
        fieldsJson = fieldsJson == null || fieldsJson.isBlank() ? "[]" : fieldsJson;
        status = status == null ? AgentQuestionCardStatusEnum.OPEN : status;
        answerFields = answerFields == null ? List.of() : List.copyOf(answerFields);
        Set<String> fieldNames = new HashSet<>();
        if (answerFields.stream().anyMatch(field -> !fieldNames.add(field.name()))) {
            throw new IllegalArgumentException("QuestionCard 回答字段名不能重复");
        }
        answerTurnId = answerTurnId == null || answerTurnId.isBlank() ? null : answerTurnId.trim();
        answerEnqueueStatus = answerEnqueueStatus == null
                ? AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE : answerEnqueueStatus;
        if (answerEnqueueStatus.requiresAnswerTurn() && answerTurnId == null) {
            throw new IllegalArgumentException("回答入队状态必须关联 answerTurnId");
        }
        if (answerEnqueueStatus == AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE && answerTurnId != null) {
            throw new IllegalArgumentException("AVAILABLE 状态不能关联 answerTurnId");
        }
        if (status == AgentQuestionCardStatusEnum.OPEN && answeredAt != null) {
            throw new IllegalArgumentException("开放 QuestionCard 不能具有回答时间");
        }
        if (status != AgentQuestionCardStatusEnum.OPEN
                && (answeredAt == null || answerTurnId == null
                || answerEnqueueStatus != AgentQuestionCardAnswerEnqueueStatusEnum.CONSUMED)) {
            throw new IllegalArgumentException("已结束 QuestionCard 必须具有已消费的回答 Turn");
        }
    }

    public AgentQuestionCardModel(
            String questionId,
            String runId,
            String threadId,
            String turnId,
            String userId,
            AgentQuestionCardResumeTargetEnum resumeTarget,
            String title,
            String prompt,
            String fieldsJson,
            AgentQuestionCardStatusEnum status,
            Instant createdAt
    ) {
        this(questionId, runId, threadId, turnId, userId, resumeTarget, 0, 0, title, prompt, fieldsJson,
                status, createdAt, null, null, AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE, List.of());
    }

    public static AgentQuestionCardModel agent(
            String questionId, String threadId, String turnId, String userId,
            String title, String prompt, String fieldsJson,
            List<AgentWorkflowQuestionFieldModel> fields, Instant createdAt
    ) {
        return new AgentQuestionCardModel(questionId, null, threadId, turnId, userId,
                AgentQuestionCardResumeTargetEnum.AGENT, 0, 0, title, prompt, fieldsJson,
                AgentQuestionCardStatusEnum.OPEN, createdAt, null, null,
                AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE, fields);
    }

    public static AgentQuestionCardModel workflow(
            String questionId, String runId, String threadId, String turnId, String userId,
            int stepNo, String title, String prompt, String fieldsJson,
            List<AgentWorkflowQuestionFieldModel> fields, Instant createdAt
    ) {
        return new AgentQuestionCardModel(questionId, runId, threadId, turnId, userId,
                AgentQuestionCardResumeTargetEnum.WORKFLOW, stepNo, 0, title, prompt, fieldsJson,
                AgentQuestionCardStatusEnum.OPEN, createdAt, null, null,
                AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE, fields);
    }

    public AgentQuestionCardModel reserveAnswerTurn(String reservedAnswerTurnId) {
        if (status != AgentQuestionCardStatusEnum.OPEN
                || answerEnqueueStatus != AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE) {
            throw new IllegalStateException("QuestionCard 当前不可预留回答 Turn");
        }
        return copy(version + 1, reservedAnswerTurnId,
                AgentQuestionCardAnswerEnqueueStatusEnum.RESERVED, status, answeredAt);
    }

    public AgentQuestionCardModel answerTurnEnqueued() {
        if (answerEnqueueStatus != AgentQuestionCardAnswerEnqueueStatusEnum.RESERVED || answerTurnId == null) {
            throw new IllegalStateException("回答 Turn 尚未处于可入队的预留状态");
        }
        return copy(version + 1, answerTurnId,
                AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED, status, answeredAt);
    }

    public AgentQuestionCardModel releaseAnswerTurn() {
        if (status != AgentQuestionCardStatusEnum.OPEN
                || !answerEnqueueStatus.requiresAnswerTurn()
                || answerEnqueueStatus == AgentQuestionCardAnswerEnqueueStatusEnum.CONSUMED) {
            throw new IllegalStateException("QuestionCard 当前没有可释放的回答 Turn");
        }
        return copy(version + 1, null, AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE,
                status, null);
    }

    public AgentQuestionCardModel answer(Instant at) {
        if (answerEnqueueStatus != AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED || answerTurnId == null) {
            throw new IllegalStateException("QuestionCard 回答 Turn 尚未入队");
        }
        return copy(version + 1, answerTurnId, AgentQuestionCardAnswerEnqueueStatusEnum.CONSUMED,
                AgentQuestionCardStatusEnum.ANSWERED, at);
    }

    public AgentQuestionCardModel cancel(Instant at) {
        if (answerEnqueueStatus != AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED || answerTurnId == null) {
            throw new IllegalStateException("QuestionCard 取消 Turn 尚未入队");
        }
        return copy(version + 1, answerTurnId, AgentQuestionCardAnswerEnqueueStatusEnum.CONSUMED,
                AgentQuestionCardStatusEnum.CANCELLED, at);
    }

    public Map<String, String> validateAnswers(Map<String, String> submittedAnswers) {
        if (answerFields.isEmpty()) {
            throw new IllegalStateException("QuestionCard 缺少可验证的回答 schema");
        }
        if (submittedAnswers == null || submittedAnswers.isEmpty()) {
            throw new IllegalArgumentException("QuestionCard 回答不能为空");
        }
        Set<String> allowedNames = answerFields.stream().map(AgentWorkflowQuestionFieldModel::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (submittedAnswers.keySet().stream().anyMatch(name -> name == null || !allowedNames.contains(name))) {
            throw new IllegalArgumentException("QuestionCard 回答包含未知字段");
        }
        Map<String, String> validated = new LinkedHashMap<>();
        for (AgentWorkflowQuestionFieldModel field : answerFields) {
            if (!submittedAnswers.containsKey(field.name())) {
                if (field.required()) {
                    throw new IllegalArgumentException("QuestionCard 缺少必填字段：" + field.name());
                }
                continue;
            }
            String value = submittedAnswers.get(field.name());
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty() || normalized.length() > field.maxLength()) {
                throw new IllegalArgumentException("QuestionCard 回答字段不合法：" + field.name());
            }
            if (!field.options().isEmpty() && !field.options().contains(normalized) && !field.allowCustom()) {
                throw new IllegalArgumentException("QuestionCard 回答选项不合法：" + field.name());
            }
            validated.put(field.name(), normalized);
        }
        return Map.copyOf(validated);
    }

    private AgentQuestionCardModel copy(long nextVersion, String nextAnswerTurnId,
                                        AgentQuestionCardAnswerEnqueueStatusEnum nextEnqueueStatus,
                                        AgentQuestionCardStatusEnum nextStatus, Instant nextAnsweredAt) {
        return new AgentQuestionCardModel(questionId, runId, threadId, turnId, userId, resumeTarget, stepNo,
                nextVersion, title, prompt, fieldsJson, nextStatus, createdAt, nextAnsweredAt, nextAnswerTurnId,
                nextEnqueueStatus, answerFields);
    }

    private static String identity(String value, String name) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return normalized;
    }
}
