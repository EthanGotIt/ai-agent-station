package cn.ethan.infrastructure.agent.workflow.persistence;

import cn.ethan.core.agent.thread.AgentInteractionTypeEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardAnswerEnqueueStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardModel;
import cn.ethan.core.agent.workflow.AgentQuestionCardStatusEnum;
import cn.ethan.core.agent.workflow.AgentQuestionCardStore;
import cn.ethan.core.agent.workflow.AgentQuestionFieldModel;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * 类型职责：以 Thread 行锁和版本 CAS 持久化独立 QuestionCard。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Repository
public class MybatisAgentQuestionCardStore implements AgentQuestionCardStore {

    private final AgentQuestionCardMapper mapper;
    private final AgentThreadMapper threadMapper;
    private final ObjectMapper objectMapper;

    public MybatisAgentQuestionCardStore(AgentQuestionCardMapper mapper,
                                         AgentThreadMapper threadMapper,
                                         ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.threadMapper = threadMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgentQuestionCardModel> find(String userId, String questionId) {
        AgentQuestionCardEntity entity = mapper.selectById(questionId);
        return entity == null || !userId.equals(entity.getUserId())
                ? Optional.empty() : Optional.of(toModel(entity));
    }

    @Override
    public Optional<AgentQuestionCardModel> findOpen(String userId, String threadId) {
        return Optional.ofNullable(mapper.selectOpen(userId, threadId)).map(this::toModel);
    }

    @Override
    @Transactional
    public void create(AgentQuestionCardModel question) {
        requireInitial(question);
        AgentThreadEntity thread = threadMapper.selectForUpdate(question.threadId());
        if (thread == null || !question.userId().equals(thread.getUserId())) {
            throw new IllegalStateException("QuestionCard 所属 Thread 不存在");
        }
        if (thread.getOpenInteractionId() != null || thread.getOpenQuestionId() != null
                || mapper.selectOpen(question.userId(), question.threadId()) != null) {
            throw new IllegalStateException("同一 Thread 只能存在一个开放交互");
        }
        mapper.insert(toEntity(question));
        if (threadMapper.setOpenInteraction(question.threadId(), question.userId(),
                AgentInteractionTypeEnum.QUESTION_CARD.name(), question.questionId(), question.createdAt()) != 1) {
            throw new IllegalStateException("QuestionCard 开放指针已被其他事务占用");
        }
    }

    @Override
    @Transactional
    public OptionalLong reserveAnswerTurn(String userId, String questionId, long expectedVersion,
                                          String answerTurnId) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0) {
            return OptionalLong.empty();
        }
        AgentQuestionCardEntity question = mapper.selectById(questionId);
        if (question == null || !userId.equals(question.getUserId())) {
            return OptionalLong.empty();
        }
        AgentThreadEntity thread = threadMapper.selectForUpdate(question.getThreadId());
        if (thread == null || !questionId.equals(thread.getOpenInteractionId())
                || !AgentInteractionTypeEnum.QUESTION_CARD.name().equals(thread.getOpenInteractionType())) {
            return OptionalLong.empty();
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentQuestionCardEntity>()
                .eq("QUESTION_ID", questionId).eq("USER_ID", userId)
                .eq("VERSION_NO", expectedVersion).eq("STATUS", AgentQuestionCardStatusEnum.OPEN.name())
                .eq("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE.name())
                .isNull("ANSWER_TURN_ID")
                .set("ANSWER_TURN_ID", answerTurnId)
                .set("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.RESERVED.name())
                .set("VERSION_NO", expectedVersion + 1));
        return updated == 1 ? OptionalLong.of(expectedVersion + 1) : OptionalLong.empty();
    }

    @Override
    public OptionalLong markAnswerTurnEnqueued(String userId, String questionId, long expectedVersion,
                                               String answerTurnId) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0) {
            return OptionalLong.empty();
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentQuestionCardEntity>()
                .eq("QUESTION_ID", questionId).eq("USER_ID", userId)
                .eq("VERSION_NO", expectedVersion).eq("STATUS", AgentQuestionCardStatusEnum.OPEN.name())
                .eq("ANSWER_TURN_ID", answerTurnId)
                .eq("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.RESERVED.name())
                .set("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED.name())
                .set("VERSION_NO", expectedVersion + 1));
        return updated == 1 ? OptionalLong.of(expectedVersion + 1) : OptionalLong.empty();
    }

    @Override
    public boolean releaseAnswerTurn(String userId, String questionId, long expectedVersion, String answerTurnId) {
        if (blank(userId) || blank(questionId) || blank(answerTurnId) || expectedVersion < 0) {
            return false;
        }
        return mapper.update(null, new UpdateWrapper<AgentQuestionCardEntity>()
                .eq("QUESTION_ID", questionId).eq("USER_ID", userId)
                .eq("VERSION_NO", expectedVersion).eq("STATUS", AgentQuestionCardStatusEnum.OPEN.name())
                .eq("ANSWER_TURN_ID", answerTurnId)
                .in("ANSWER_ENQUEUE_STATUS", List.of(
                        AgentQuestionCardAnswerEnqueueStatusEnum.RESERVED.name(),
                        AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED.name()))
                .set("ANSWER_TURN_ID", null)
                .set("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE.name())
                .set("VERSION_NO", expectedVersion + 1)) == 1;
    }

    @Override
    @Transactional
    public boolean closeAnswerTurn(String userId, String questionId, long expectedVersion,
                                   String answerTurnId, AgentQuestionCardStatusEnum terminalStatus,
                                   Instant answeredAt) {
        if (terminalStatus != AgentQuestionCardStatusEnum.ANSWERED
                && terminalStatus != AgentQuestionCardStatusEnum.CANCELLED) {
            throw new IllegalArgumentException("QuestionCard 只能以 ANSWERED 或 CANCELLED 关闭");
        }
        AgentQuestionCardEntity question = mapper.selectById(questionId);
        if (question == null || !userId.equals(question.getUserId())) {
            return false;
        }
        AgentThreadEntity thread = threadMapper.selectForUpdate(question.getThreadId());
        if (thread == null || !questionId.equals(thread.getOpenInteractionId())) {
            return false;
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentQuestionCardEntity>()
                .eq("QUESTION_ID", questionId).eq("USER_ID", userId)
                .eq("VERSION_NO", expectedVersion).eq("STATUS", AgentQuestionCardStatusEnum.OPEN.name())
                .eq("ANSWER_TURN_ID", answerTurnId)
                .eq("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.ENQUEUED.name())
                .set("STATUS", terminalStatus.name())
                .set("ANSWER_ENQUEUE_STATUS", AgentQuestionCardAnswerEnqueueStatusEnum.CONSUMED.name())
                .set("ANSWERED_AT", answeredAt)
                .set("VERSION_NO", expectedVersion + 1));
        if (updated != 1) {
            return false;
        }
        if (threadMapper.clearOpenInteraction(question.getThreadId(), userId,
                AgentInteractionTypeEnum.QUESTION_CARD.name(), questionId, answeredAt) != 1) {
            throw new IllegalStateException("QuestionCard 已关闭但 Thread 开放指针未清理");
        }
        return true;
    }

    private void requireInitial(AgentQuestionCardModel question) {
        if (question.status() != AgentQuestionCardStatusEnum.OPEN || question.version() != 0
                || question.answerEnqueueStatus() != AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE
                || question.answerTurnId() != null || question.answeredAt() != null) {
            throw new IllegalArgumentException("create 只接受 OPEN/v0/AVAILABLE 初始 QuestionCard");
        }
    }

    private AgentQuestionCardEntity toEntity(AgentQuestionCardModel model) {
        AgentQuestionCardEntity entity = new AgentQuestionCardEntity();
        entity.setQuestionId(model.questionId());
        entity.setRunId(model.runId());
        entity.setThreadId(model.threadId());
        entity.setTurnId(model.turnId());
        entity.setUserId(model.userId());
        entity.setResumeTarget(model.resumeTarget().name());
        entity.setStepNo(model.stepNo());
        entity.setVersionNo(model.version());
        entity.setAnswerTurnId(model.answerTurnId());
        entity.setAnswerEnqueueStatus(model.answerEnqueueStatus().name());
        entity.setTitle(model.title());
        entity.setPrompt(model.prompt());
        entity.setFieldsJson(model.fieldsJson());
        entity.setStatus(model.status().name());
        entity.setCreatedAt(model.createdAt());
        entity.setAnsweredAt(model.answeredAt());
        return entity;
    }

    private AgentQuestionCardModel toModel(AgentQuestionCardEntity entity) {
        return new AgentQuestionCardModel(entity.getQuestionId(), entity.getRunId(), entity.getThreadId(),
                entity.getTurnId(), entity.getUserId(),
                entity.getResumeTarget() == null ? null
                        : cn.ethan.core.agent.workflow.AgentQuestionCardResumeTargetEnum.valueOf(entity.getResumeTarget()),
                entity.getStepNo() == null ? 0 : entity.getStepNo(), entity.getVersionNo() == null ? 0 : entity.getVersionNo(),
                entity.getTitle(), entity.getPrompt(), entity.getFieldsJson(),
                AgentQuestionCardStatusEnum.valueOf(entity.getStatus()), entity.getCreatedAt(), entity.getAnsweredAt(),
                entity.getAnswerTurnId(), parseEnqueueStatus(entity.getAnswerEnqueueStatus()),
                parseAnswerFields(entity.getFieldsJson()));
    }

    private List<AgentQuestionFieldModel> parseAnswerFields(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(fieldsJson);
            JsonNode fields = root != null && root.isObject() && root.has("fields") ? root.path("fields") : root;
            if (fields == null || !fields.isArray()) {
                return List.of();
            }
            List<AgentQuestionFieldModel> result = new ArrayList<>();
            for (JsonNode field : fields) {
                List<String> options = new ArrayList<>();
                JsonNode optionNode = field.path("options");
                if (optionNode.isArray()) {
                    optionNode.forEach(option -> options.add(option.asString()));
                }
                result.add(new AgentQuestionFieldModel(field.path("name").asString(),
                        field.path("required").asBoolean(false), field.path("maxLength").asInt(256),
                        options, field.path("allowCustom").asBoolean(false)));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            throw new IllegalStateException("无法解析 QuestionCard 字段 schema", failure);
        }
    }

    private AgentQuestionCardAnswerEnqueueStatusEnum parseEnqueueStatus(String value) {
        return value == null || value.isBlank() ? AgentQuestionCardAnswerEnqueueStatusEnum.AVAILABLE
                : AgentQuestionCardAnswerEnqueueStatusEnum.valueOf(value);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
