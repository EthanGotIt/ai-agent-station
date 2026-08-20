package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowQuestionModel;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStore;
import cn.ethan.core.agent.workflow.AgentWorkflowQuestionStatusEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 类型职责：持久化 Workflow QuestionCard，并以乐观版本关闭开放问题。
 *
 * @author ethan
 * @date 2026-08-20
 */
@Repository
public final class MybatisAgentWorkflowQuestionStore implements AgentWorkflowQuestionStore {

    private final AgentWorkflowQuestionMapper mapper;

    public MybatisAgentWorkflowQuestionStore(AgentWorkflowQuestionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AgentWorkflowQuestionModel> findOpenQuestion(String userId, String threadId) {
        return Optional.ofNullable(mapper.selectOpen(userId, threadId)).map(MybatisAgentWorkflowQuestionStore::toModel);
    }

    @Override
    public Optional<AgentWorkflowQuestionModel> findOpenQuestionByRun(String userId, String runId) {
        return Optional.ofNullable(mapper.selectOpenByRun(userId, runId)).map(MybatisAgentWorkflowQuestionStore::toModel);
    }

    @Override
    @Transactional
    public void saveQuestion(AgentWorkflowQuestionModel question) {
        if (!mapper.selectOpenForUpdate(question.threadId()).isEmpty()) {
            throw new IllegalStateException("同一 Thread 只能存在一个开放 QuestionCard");
        }
        mapper.insert(toEntity(question));
    }

    @Override
    @Transactional
    public void answerQuestion(AgentWorkflowQuestionModel question) {
        long previousVersion = Math.max(0L, question.version() - 1);
        int updated = mapper.update(null, new UpdateWrapper<AgentWorkflowQuestionEntity>()
                .eq("QUESTION_ID", question.questionId())
                .eq("STATUS", "OPEN")
                .eq("VERSION_NO", previousVersion)
                .set("VERSION_NO", question.version())
                .set("STATUS", question.status().name())
                .set("ANSWERED_AT", question.answeredAt()));
        if (updated != 1) {
            throw new IllegalStateException("QuestionCard 已被其他请求处理");
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
        entity.setTitle(model.title());
        entity.setPrompt(model.prompt());
        entity.setFieldsJson(model.fieldsJson());
        entity.setStatus(model.status().name());
        entity.setCreatedAt(model.createdAt());
        entity.setAnsweredAt(model.answeredAt());
        return entity;
    }

    private static AgentWorkflowQuestionModel toModel(AgentWorkflowQuestionEntity entity) {
        return new AgentWorkflowQuestionModel(entity.getRunId(), entity.getThreadId(), entity.getTurnId(), entity.getUserId(),
                entity.getQuestionId(), entity.getCheckpointId(), value(entity.getVersionNo()), entity.getTitle(),
                entity.getPrompt(), entity.getFieldsJson(), AgentWorkflowQuestionStatusEnum.valueOf(entity.getStatus()),
                entity.getCreatedAt(), entity.getAnsweredAt());
    }

    private static long value(Long value) {
        return value == null ? 0L : value;
    }
}
