package cn.ethan.core.agent.workflow;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 持久化 QuestionCard 检查点。
 *
 * @author ethan
 * @date 2026-08-19
 */
public record AgentWorkflowQuestionModel(
        String runId,
        String threadId,
        String turnId,
        String userId,
        String questionId,
        String checkpointId,
        int stepNo,
        long version,
        String title,
        String prompt,
        String fieldsJson,
        AgentWorkflowQuestionStatusEnum status,
        Instant createdAt,
        Instant answeredAt,
        String answerTurnId,
        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum answerEnqueueStatus,
        List<AgentWorkflowQuestionFieldModel> answerFields
) {
    public AgentWorkflowQuestionModel {
        if (runId == null || runId.isBlank() || threadId == null || threadId.isBlank()
                || turnId == null || turnId.isBlank() || userId == null || userId.isBlank()
                || questionId == null || questionId.isBlank()
                || checkpointId == null || checkpointId.isBlank()) {
            throw new IllegalArgumentException("question identity must not be blank");
        }
        if (stepNo < 0 || version < 0 || createdAt == null) {
            throw new IllegalArgumentException("question version and createdAt must be valid");
        }
        title = title == null ? "需要确认" : title;
        prompt = prompt == null ? "" : prompt;
        fieldsJson = fieldsJson == null ? "[]" : fieldsJson;
        answerFields = answerFields == null ? List.of() : List.copyOf(answerFields);
        Set<String> fieldNames = new HashSet<>();
        if (answerFields.stream().anyMatch(field -> !fieldNames.add(field.name()))) {
            throw new IllegalArgumentException("QuestionCard 回答字段名不能重复");
        }
        if (status == null) {
            throw new IllegalArgumentException("question status must not be null");
        }
        answerTurnId = answerTurnId == null || answerTurnId.isBlank() ? null : answerTurnId;
        answerEnqueueStatus = answerEnqueueStatus == null
                ? status == AgentWorkflowQuestionStatusEnum.ANSWERED
                ? AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED
                : AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE
                : answerEnqueueStatus;
        if ((answerEnqueueStatus.requiresAnswerTurn()
                || answerEnqueueStatus == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED)
                && answerTurnId == null) {
            throw new IllegalArgumentException("回答入队状态必须关联 answerTurnId");
        }
        if (answerEnqueueStatus == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE
                && answerTurnId != null) {
            throw new IllegalArgumentException("AVAILABLE 状态不能关联 answerTurnId");
        }
        if (status == AgentWorkflowQuestionStatusEnum.OPEN
                && (answeredAt != null
                || answerEnqueueStatus == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED)) {
            throw new IllegalArgumentException("开放 QuestionCard 不能具有终态回答字段");
        }
        if (status == AgentWorkflowQuestionStatusEnum.ANSWERED
                && (answeredAt == null
                || answerEnqueueStatus != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED)) {
            throw new IllegalArgumentException("已回答 QuestionCard 必须具有消费状态和回答时间");
        }
    }

    /** 兼容未持久化步骤序号的旧 Question 调用边界。 */
    public AgentWorkflowQuestionModel(
            String runId,
            String threadId,
            String turnId,
            String userId,
            String questionId,
            String checkpointId,
            long version,
            String title,
            String prompt,
            String fieldsJson,
            AgentWorkflowQuestionStatusEnum status,
            Instant createdAt,
            Instant answeredAt,
            String answerTurnId,
            AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum answerEnqueueStatus,
            List<AgentWorkflowQuestionFieldModel> answerFields
    ) {
        this(runId, threadId, turnId, userId, questionId, checkpointId, 0, version, title, prompt,
                fieldsJson, status, createdAt, answeredAt, answerTurnId, answerEnqueueStatus, answerFields);
    }

    /** 保留既有持久状态构造边界；新建可回答 QuestionCard 应显式提供 answerFields。 */
    public AgentWorkflowQuestionModel(
            String runId,
            String threadId,
            String turnId,
            String userId,
            String questionId,
            String checkpointId,
            long version,
            String title,
            String prompt,
            String fieldsJson,
            AgentWorkflowQuestionStatusEnum status,
            Instant createdAt,
            Instant answeredAt,
            String answerTurnId,
            AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum answerEnqueueStatus
    ) {
        this(runId, threadId, turnId, userId, questionId, checkpointId, version, title, prompt, fieldsJson,
                status, createdAt, answeredAt, answerTurnId, answerEnqueueStatus, List.of());
    }

    /** 创建尚未预留回答 Turn 的初始 QuestionCard。 */
    public AgentWorkflowQuestionModel(
            String runId,
            String threadId,
            String turnId,
            String userId,
            String questionId,
            String checkpointId,
            long version,
            String title,
            String prompt,
            String fieldsJson,
            AgentWorkflowQuestionStatusEnum status,
            Instant createdAt,
            Instant answeredAt
    ) {
        this(runId, threadId, turnId, userId, questionId, checkpointId, version, title, prompt, fieldsJson,
                status, createdAt, answeredAt, null,
                AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE, List.of());
    }

    /** 以 QuestionCard 当前版本原子预留唯一回答 Turn。 */
    public AgentWorkflowQuestionModel reserveAnswerTurn(String reservedAnswerTurnId) {
        if (status != AgentWorkflowQuestionStatusEnum.OPEN
                || answerEnqueueStatus != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE) {
            throw new IllegalStateException("QuestionCard 当前不可预留回答 Turn");
        }
        if (reservedAnswerTurnId == null || reservedAnswerTurnId.isBlank()) {
            throw new IllegalArgumentException("answerTurnId 不能为空");
        }
        return new AgentWorkflowQuestionModel(runId, threadId, turnId, userId, questionId, checkpointId, stepNo,
                version + 1, title, prompt, fieldsJson, status, createdAt, answeredAt, reservedAnswerTurnId,
                AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED, answerFields);
    }

    /** 标记预留 Turn 已完成本地入队，供重启恢复判断是否需要继续创建或等待该 Turn。 */
    public AgentWorkflowQuestionModel answerTurnEnqueued() {
        if (answerEnqueueStatus != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED
                || answerTurnId == null) {
            throw new IllegalStateException("回答 Turn 尚未处于可入队的预留状态");
        }
        return new AgentWorkflowQuestionModel(runId, threadId, turnId, userId, questionId, checkpointId, stepNo,
                version + 1, title, prompt, fieldsJson, status, createdAt, answeredAt, answerTurnId,
                AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED, answerFields);
    }

    /** 取消或超时只释放当前预留并推进版本，旧回答因此无法按旧版本再次成功。 */
    public AgentWorkflowQuestionModel releaseAnswerTurn() {
        if (status != AgentWorkflowQuestionStatusEnum.OPEN
                || !answerEnqueueStatus.requiresAnswerTurn()
                || answerEnqueueStatus == AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED) {
            throw new IllegalStateException("QuestionCard 当前没有可释放的回答 Turn");
        }
        return new AgentWorkflowQuestionModel(runId, threadId, turnId, userId, questionId, checkpointId, stepNo,
                version + 1, title, prompt, fieldsJson, status, createdAt, answeredAt, null,
                AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE, answerFields);
    }

    /** 按持久化 QuestionCard schema 校验并规范化结构化 answers。 */
    public Map<String, String> validateAnswers(Map<String, String> submittedAnswers) {
        if (answerFields.isEmpty()) {
            throw new IllegalStateException("QuestionCard 缺少可验证的回答 schema");
        }
        if (submittedAnswers == null || submittedAnswers.isEmpty()) {
            throw new IllegalArgumentException("QuestionCard 回答不能为空");
        }
        Set<String> allowedNames = answerFields.stream()
                .map(AgentWorkflowQuestionFieldModel::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (submittedAnswers.keySet().stream().anyMatch(name -> name == null || !allowedNames.contains(name))) {
            throw new IllegalArgumentException("QuestionCard 回答包含未知字段");
        }
        Map<String, String> validated = new LinkedHashMap<>();
        for (AgentWorkflowQuestionFieldModel field : answerFields) {
            boolean present = submittedAnswers.containsKey(field.name());
            if (!present) {
                if (field.required()) {
                    throw new IllegalArgumentException("QuestionCard 缺少必填字段：" + field.name());
                }
                continue;
            }
            String value = submittedAnswers.get(field.name());
            String normalized = value == null ? "" : value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("QuestionCard 回答字段不能为空：" + field.name());
            }
            if (normalized.length() > field.maxLength()) {
                throw new IllegalArgumentException("QuestionCard 回答字段过长：" + field.name());
            }
            if (!field.options().isEmpty()
                    && !field.options().contains(normalized)
                    && !field.allowCustom()) {
                throw new IllegalArgumentException("QuestionCard 回答选项不合法：" + field.name());
            }
            validated.put(field.name(), normalized);
        }
        return Map.copyOf(validated);
    }
}
