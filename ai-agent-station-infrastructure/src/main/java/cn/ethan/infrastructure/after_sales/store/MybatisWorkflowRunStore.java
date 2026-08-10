package cn.ethan.infrastructure.after_sales.store;

import cn.ethan.core.workflow.enums.WorkflowRunStatusEnum;
import cn.ethan.core.workflow.model.WorkflowRunModel;
import cn.ethan.core.workflow.model.WorkflowQuestionModel;
import cn.ethan.core.workflow.port.WorkflowRunStore;
import cn.ethan.infrastructure.after_sales.entity.WorkflowRunEntity;
import cn.ethan.infrastructure.after_sales.mapper.WorkflowRunMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * MyBatis Workflow 运行存储：以版本条件更新实现跨请求的恢复互斥。
 *
 * @author ethan
 * @date 2026-08-07
 */
@Component
public final class MybatisWorkflowRunStore implements WorkflowRunStore {

    private static final TypeReference<Map<String, String>> STATE_TYPE = new TypeReference<>() {
    };

    private final WorkflowRunMapper mapper;
    private final ObjectMapper objectMapper;

    public MybatisWorkflowRunStore(WorkflowRunMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(WorkflowRunModel run) {
        if (mapper.insert(toEntity(run)) != 1) {
            throw new IllegalStateException("workflow run was not created");
        }
    }

    @Override
    public Optional<WorkflowRunModel> findOwned(String runId, String userId, String sessionId) {
        WorkflowRunEntity entity = mapper.selectById(runId);
        if (entity == null || !entity.getUserId().equals(userId) || !entity.getSessionId().equals(sessionId)) {
            return Optional.empty();
        }
        return Optional.of(toModel(entity));
    }

    @Override
    public boolean compareAndSet(WorkflowRunModel expected, WorkflowRunModel updated) {
        if (!expected.runId().equals(updated.runId()) || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("workflow run optimistic version transition is invalid");
        }
        LambdaUpdateWrapper<WorkflowRunEntity> update = new LambdaUpdateWrapper<WorkflowRunEntity>()
                .eq(WorkflowRunEntity::getRunId, expected.runId())
                .eq(WorkflowRunEntity::getVersion, expected.version())
                .set(WorkflowRunEntity::getStatus, updated.status().name())
                .set(WorkflowRunEntity::getCheckpointId, updated.checkpointId())
                .set(WorkflowRunEntity::getVersion, updated.version())
                .set(WorkflowRunEntity::getStateJson, writeState(updated.state()))
                .set(WorkflowRunEntity::getQuestionJson, writeQuestion(updated.question()))
                .set(WorkflowRunEntity::getResultContent, updated.resultContent())
                .set(WorkflowRunEntity::getUpdatedAt, updated.updatedAt());
        return mapper.update(null, update) == 1;
    }

    private WorkflowRunEntity toEntity(WorkflowRunModel run) {
        WorkflowRunEntity entity = new WorkflowRunEntity();
        entity.setRunId(run.runId());
        entity.setUserId(run.userId());
        entity.setSessionId(run.sessionId());
        entity.setDomainId(run.domainId());
        entity.setWorkflowId(run.workflowId());
        entity.setWorkflowVersion(run.workflowVersion());
        entity.setOperation(run.operation());
        entity.setStatus(run.status().name());
        entity.setCheckpointId(run.checkpointId());
        entity.setVersion(run.version());
        entity.setStateJson(writeState(run.state()));
        entity.setQuestionJson(writeQuestion(run.question()));
        entity.setResultContent(run.resultContent());
        entity.setCreatedAt(run.createdAt());
        entity.setUpdatedAt(run.updatedAt());
        return entity;
    }

    private WorkflowRunModel toModel(WorkflowRunEntity entity) {
        return new WorkflowRunModel(
                entity.getRunId(),
                entity.getUserId(),
                entity.getSessionId(),
                entity.getDomainId(),
                entity.getWorkflowId(),
                entity.getWorkflowVersion(),
                entity.getOperation(),
                WorkflowRunStatusEnum.valueOf(entity.getStatus()),
                entity.getCheckpointId(),
                entity.getVersion(),
                readState(entity.getStateJson()),
                readQuestion(entity.getQuestionJson()),
                entity.getResultContent(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private String writeState(Map<String, String> state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("workflow run state cannot be serialized", failure);
        }
    }

    private Map<String, String> readState(String stateJson) {
        try {
            return stateJson == null || stateJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(stateJson, STATE_TYPE);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("workflow run state cannot be restored", failure);
        }
    }

    private String writeQuestion(WorkflowQuestionModel question) {
        try {
            return question == null ? "" : objectMapper.writeValueAsString(question);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("workflow question cannot be serialized", failure);
        }
    }

    private WorkflowQuestionModel readQuestion(String questionJson) {
        try {
            return questionJson == null || questionJson.isBlank()
                    ? null
                    : objectMapper.readValue(questionJson, WorkflowQuestionModel.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("workflow question cannot be restored", failure);
        }
    }
}
