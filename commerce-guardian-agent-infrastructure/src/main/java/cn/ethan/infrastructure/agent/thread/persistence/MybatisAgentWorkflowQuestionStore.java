package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionFieldModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 类型职责：持久化 Workflow QuestionCard，并以乐观版本关闭开放问题。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public class MybatisAgentWorkflowQuestionStore implements AgentWorkflowQuestionStore {

    private final AgentWorkflowQuestionMapper mapper;
    private final AgentThreadMapper threadMapper;
    private final ObjectMapper objectMapper;

    public MybatisAgentWorkflowQuestionStore(
            AgentWorkflowQuestionMapper mapper,
            AgentThreadMapper threadMapper,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.threadMapper = threadMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
        return Optional.ofNullable(mapper.selectOpen(userId, threadId)).map(this::toModel);
    }

    @Override
    public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
        return Optional.ofNullable(mapper.selectOpenByRun(userId, runId)).map(this::toModel);
    }

    @Override
    @Transactional
    public void saveQuestion(AgentWorkflowQuestionModel question) {
        requireInitialQuestion(question);
        AgentThreadEntity thread = threadMapper.selectForUpdate(question.threadId());
        if (thread == null || !question.userId().equals(thread.getUserId())) {
            throw new IllegalStateException("QuestionCard 所属 Thread 不存在");
        }
        if (thread.getOpenQuestionId() != null || mapper.selectOpen(question.userId(), question.threadId()) != null) {
            throw new IllegalStateException("同一 Thread 只能存在一个开放 QuestionCard");
        }
        mapper.insert(toEntity(question));
        if (threadMapper.setOpenQuestion(question.threadId(), question.userId(), question.questionId(),
                question.createdAt()) != 1) {
            throw new IllegalStateException("QuestionCard 开放指针已被其他事务占用");
        }
    }

    @Override
    @Transactional
    public OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0) {
            return OptionalLong.empty();
        }
        AgentWorkflowQuestionEntity question = mapper.selectById(questionId);
        if (question == null || !userId.equals(question.getUserId())) {
            return OptionalLong.empty();
        }
        AgentThreadEntity thread = threadMapper.selectForUpdate(question.getThreadId());
        if (thread == null || !userId.equals(thread.getUserId())
                || !questionId.equals(thread.getOpenQuestionId())) {
            return OptionalLong.empty();
        }
        UpdateWrapper<AgentWorkflowQuestionEntity> wrapper = new UpdateWrapper<AgentWorkflowQuestionEntity>()
                .eq("USER_ID", userId)
                .eq("QUESTION_ID", questionId)
                .eq("STATUS", "OPEN")
                .eq("VERSION_NO", expectedVersion)
                .isNull("ANSWER_TURN_ID")
                .and(value -> value.isNull("ANSWER_ENQUEUE_STATUS")
                        .or().eq("ANSWER_ENQUEUE_STATUS", answerStatus(
                                AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE)))
                .set("VERSION_NO", expectedVersion + 1)
                .set("ANSWER_TURN_ID", answerTurnId)
                .set("ANSWER_ENQUEUE_STATUS", answerStatus(
                        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED));
        return mapper.update(null, wrapper) == 1
                ? OptionalLong.of(expectedVersion + 1) : OptionalLong.empty();
    }

    @Override
    public OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion,
                                               String answerTurnId) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0) {
            return OptionalLong.empty();
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentWorkflowQuestionEntity>()
                .eq("USER_ID", userId)
                .eq("QUESTION_ID", questionId)
                .eq("STATUS", "OPEN")
                .eq("VERSION_NO", expectedVersion)
                .eq("ANSWER_TURN_ID", answerTurnId)
                .eq("ANSWER_ENQUEUE_STATUS", answerStatus(
                        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED))
                .set("VERSION_NO", expectedVersion + 1)
                .set("ANSWER_ENQUEUE_STATUS", answerStatus(
                        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED)));
        return updated == 1 ? OptionalLong.of(expectedVersion + 1) : OptionalLong.empty();
    }

    @Override
    public boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0) {
            return false;
        }
        return mapper.update(null, new UpdateWrapper<AgentWorkflowQuestionEntity>()
                .eq("USER_ID", userId)
                .eq("QUESTION_ID", questionId)
                .eq("STATUS", "OPEN")
                .eq("VERSION_NO", expectedVersion)
                .eq("ANSWER_TURN_ID", answerTurnId)
                .in("ANSWER_ENQUEUE_STATUS", List.of(
                        answerStatus(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.RESERVED),
                        answerStatus(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED)))
                .set("VERSION_NO", expectedVersion + 1)
                .set("ANSWER_TURN_ID", null)
                .set("ANSWER_ENQUEUE_STATUS", answerStatus(
                        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE))) == 1;
    }

    @Override
    @Transactional
    public boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                                   String answerTurnId, Instant answeredAt) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0
                || answeredAt == null) {
            return false;
        }
        AgentWorkflowQuestionEntity question = mapper.selectById(questionId);
        if (question == null || !userId.equals(question.getUserId())) {
            return false;
        }
        AgentThreadEntity thread = threadMapper.selectForUpdate(question.getThreadId());
        if (thread == null || !userId.equals(thread.getUserId())
                || !questionId.equals(thread.getOpenQuestionId())) {
            return false;
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentWorkflowQuestionEntity>()
                .eq("USER_ID", userId)
                .eq("QUESTION_ID", questionId)
                .eq("STATUS", "OPEN")
                .eq("VERSION_NO", expectedVersion)
                .eq("ANSWER_TURN_ID", answerTurnId)
                .eq("ANSWER_ENQUEUE_STATUS", answerStatus(
                        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.ENQUEUED))
                .set("VERSION_NO", expectedVersion + 1)
                .set("STATUS", AgentWorkflowQuestionStatusEnum.ANSWERED.name())
                .set("ANSWER_ENQUEUE_STATUS", answerStatus(
                        AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.CONSUMED))
                .set("ANSWERED_AT", answeredAt));
        if (updated != 1) {
            return false;
        }
        if (threadMapper.clearOpenQuestion(question.getThreadId(), userId, questionId, answeredAt) != 1) {
            throw new IllegalStateException("QuestionCard 已关闭但 Thread 开放指针未同步清理");
        }
        return true;
    }

    private static void requireInitialQuestion(AgentWorkflowQuestionModel question) {
        if (question.status() != AgentWorkflowQuestionStatusEnum.OPEN || question.version() != 0
                || question.answerEnqueueStatus()
                != AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE
                || question.answerTurnId() != null || question.answeredAt() != null) {
            throw new IllegalArgumentException("saveQuestion 只接受 OPEN/v0/AVAILABLE 初始 QuestionCard");
        }
    }

    private static AgentWorkflowQuestionEntity toEntity(AgentWorkflowQuestionModel model) {
        AgentWorkflowQuestionEntity entity = new AgentWorkflowQuestionEntity();
        entity.setQuestionId(model.questionId());
        entity.setRunId(model.runId());
        entity.setThreadId(model.threadId());
        entity.setTurnId(model.turnId());
        entity.setUserId(model.userId());
        entity.setCheckpointId(model.checkpointId());
        entity.setVersionNo(model.version());
        entity.setAnswerTurnId(model.answerTurnId());
        entity.setAnswerEnqueueStatus(answerStatus(model.answerEnqueueStatus()));
        entity.setTitle(model.title());
        entity.setPrompt(model.prompt());
        entity.setFieldsJson(model.fieldsJson());
        entity.setStatus(model.status().name());
        entity.setCreatedAt(model.createdAt());
        entity.setAnsweredAt(model.answeredAt());
        return entity;
    }

    private AgentWorkflowQuestionModel toModel(AgentWorkflowQuestionEntity entity) {
        return new AgentWorkflowQuestionModel(entity.getRunId(), entity.getThreadId(), entity.getTurnId(), entity.getUserId(),
                entity.getQuestionId(), entity.getCheckpointId(), value(entity.getVersionNo()), entity.getTitle(),
                entity.getPrompt(), entity.getFieldsJson(), AgentWorkflowQuestionStatusEnum.valueOf(entity.getStatus()),
                entity.getCreatedAt(), entity.getAnsweredAt(), entity.getAnswerTurnId(),
                parseAnswerStatus(entity.getAnswerEnqueueStatus()), parseAnswerFields(entity.getFieldsJson()));
    }

    private List<AgentWorkflowQuestionFieldModel> parseAnswerFields(String fieldsJson) {
        try {
            JsonNode root = objectMapper.readTree(fieldsJson == null ? "[]" : fieldsJson);
            JsonNode fields = root.isArray() ? root : root.path("fields");
            if (!fields.isArray()) {
                return List.of();
            }
            java.util.ArrayList<AgentWorkflowQuestionFieldModel> result = new java.util.ArrayList<>();
            for (JsonNode field : fields) {
                java.util.ArrayList<String> options = new java.util.ArrayList<>();
                if (field.path("options").isArray()) {
                    field.path("options").forEach(option -> options.add(option.asString("")));
                }
                result.add(new AgentWorkflowQuestionFieldModel(
                        field.path("name").asString(""),
                        field.path("required").asBoolean(false),
                        field.path("maxLength").asInt(256),
                        options));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("无法解析 QuestionCard 回答 schema", failure);
        }
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }

    private static String answerStatus(AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum status) {
        return (status == null ? AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.AVAILABLE : status).name();
    }

    private static AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum parseAnswerStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return AgentWorkflowQuestionStatusEnum.AnswerEnqueueStatusEnum.valueOf(status);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
