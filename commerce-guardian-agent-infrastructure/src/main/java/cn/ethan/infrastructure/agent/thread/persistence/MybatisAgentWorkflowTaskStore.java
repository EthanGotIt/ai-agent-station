package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowTaskModel;
import cn.ethan.core.agent.workflow.AgentWorkflowTaskStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowOrchestrationVersionEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import cn.ethan.core.agent.thread.AgentThreadConflictException;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：将 WorkflowTask 模型转换为 MyBatis-Plus 持久化记录。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Repository
public class MybatisAgentWorkflowTaskStore implements AgentWorkflowTaskStore {

    private final AgentWorkflowTaskMapper mapper;

    public MybatisAgentWorkflowTaskStore(AgentWorkflowTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(AgentWorkflowTaskModel task) {
        mapper.insert(toEntity(task));
    }

    @Override
    public Optional<AgentWorkflowTaskModel> find(String userId, String taskId) {
        return Optional.ofNullable(mapper.selectOwned(userId, taskId)).map(this::toModel);
    }

    @Override
    public Optional<AgentWorkflowTaskModel> findForUpdate(String userId, String taskId) {
        return Optional.ofNullable(mapper.selectOwnedForUpdate(userId, taskId)).map(this::toModel);
    }

    @Override
    public Optional<AgentWorkflowTaskModel> findBySource(
            String userId, String turnId, AgentWorkflowTypeEnum workflowType
    ) {
        if (userId == null || turnId == null || workflowType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.selectBySource(userId, turnId, workflowType.name())).map(this::toModel);
    }

    @Override
    public List<AgentWorkflowTaskModel> findRecent(String userId, String threadId, int limit) {
        if (userId == null || threadId == null || limit < 1) return List.of();
        return mapper.selectRecent(userId, threadId, Math.min(limit, 5)).stream().map(this::toModel).toList();
    }

    @Override
    public void update(AgentWorkflowTaskModel task) {
        long previousVersion = Math.max(0, task.version() - 1);
        int updated = mapper.update(toEntity(task), new UpdateWrapper<AgentWorkflowTaskEntity>()
                .eq("RUN_ID", task.taskId())
                .eq("USER_ID", task.userId())
                .eq("VERSION_NO", previousVersion)
                .eq("ORCHESTRATION_VERSION", task.orchestrationVersion().name())
                .notIn("STATUS", List.of(
                        AgentWorkflowStatusEnum.COMPLETED.name(),
                        AgentWorkflowStatusEnum.REJECTED.name(),
                        AgentWorkflowStatusEnum.FAILED.name())));
        if (updated != 1) {
            throw new IllegalStateException("WorkflowTask 版本已变化");
        }
    }

    private AgentWorkflowTaskEntity toEntity(AgentWorkflowTaskModel task) {
        AgentWorkflowTaskEntity entity = new AgentWorkflowTaskEntity();
        entity.setTaskId(task.taskId());
        entity.setThreadId(task.threadId());
        entity.setTurnId(task.turnId());
        entity.setUserId(task.userId());
        entity.setWorkflowType(task.workflowType().name());
        entity.setOrchestrationVersion(task.orchestrationVersion().name());
        entity.setStatus(task.status().name());
        entity.setVersionNo(task.version());
        entity.setStepsJson(task.stepsJson());
        entity.setStateJson(task.stateJson());
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
        return entity;
    }

    private AgentWorkflowTaskModel toModel(AgentWorkflowTaskEntity entity) {
        AgentWorkflowOrchestrationVersionEnum orchestrationVersion;
        try {
            orchestrationVersion = entity.getOrchestrationVersion() == null
                    || entity.getOrchestrationVersion().isBlank()
                    ? AgentWorkflowOrchestrationVersionEnum.LEGACY_V1
                    : AgentWorkflowOrchestrationVersionEnum.valueOf(entity.getOrchestrationVersion());
        } catch (IllegalArgumentException failure) {
            throw new AgentThreadConflictException(
                    "UNKNOWN_WORKFLOW_ORCHESTRATION_VERSION", "WorkflowTask 编排版本无法识别");
        }
        return new AgentWorkflowTaskModel(entity.getTaskId(), entity.getThreadId(), entity.getTurnId(),
                entity.getUserId(), AgentWorkflowTypeEnum.valueOf(entity.getWorkflowType()),
                AgentWorkflowStatusEnum.valueOf(entity.getStatus()),
                entity.getVersionNo() == null ? 0 : entity.getVersionNo(),
                entity.getStepsJson(), entity.getStateJson(),
                entity.getCreatedAt(), entity.getUpdatedAt(), orchestrationVersion);
    }
}
