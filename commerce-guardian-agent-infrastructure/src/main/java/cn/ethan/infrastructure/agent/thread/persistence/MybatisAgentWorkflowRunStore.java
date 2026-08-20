package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.workflow.AgentWorkflowRunModel;
import cn.ethan.core.agent.workflow.AgentWorkflowRunStore;
import cn.ethan.core.agent.workflow.AgentWorkflowStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowTypeEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 类型职责：将 WorkflowRun 模型转换为 MyBatis-Plus 持久化记录。
 * 该适配器需要保留可代理性，以承接 Spring 的异常翻译和事务边界。
 *
 * @author ethan
 * @date 2026-08-19
 */
@Repository
public class MybatisAgentWorkflowRunStore implements AgentWorkflowRunStore {

    private final AgentWorkflowRunMapper mapper;

    public MybatisAgentWorkflowRunStore(AgentWorkflowRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(AgentWorkflowRunModel run) {
        mapper.insert(toEntity(run));
    }

    @Override
    public Optional<AgentWorkflowRunModel> find(String userId, String runId) {
        return Optional.ofNullable(mapper.selectOwned(userId, runId)).map(this::toModel);
    }

    @Override
    public void update(AgentWorkflowRunModel run) {
        long previousVersion = Math.max(0, run.version() - 1);
        int updated = mapper.update(toEntity(run), new UpdateWrapper<AgentWorkflowRunEntity>()
                .eq("RUN_ID", run.runId())
                .eq("USER_ID", run.userId())
                .eq("VERSION_NO", previousVersion)
                .notIn("STATUS", List.of(
                        AgentWorkflowStatusEnum.COMPLETED.name(),
                        AgentWorkflowStatusEnum.REJECTED.name(),
                        AgentWorkflowStatusEnum.FAILED.name())));
        if (updated != 1) {
            throw new IllegalStateException("WorkflowRun 版本已变化");
        }
    }

    private AgentWorkflowRunEntity toEntity(AgentWorkflowRunModel run) {
        AgentWorkflowRunEntity entity = new AgentWorkflowRunEntity();
        entity.setRunId(run.runId());
        entity.setThreadId(run.threadId());
        entity.setTurnId(run.turnId());
        entity.setUserId(run.userId());
        entity.setWorkflowType(run.workflowType().name());
        entity.setStatus(run.status().name());
        entity.setVersionNo(run.version());
        entity.setCreatedAt(run.createdAt());
        entity.setUpdatedAt(run.updatedAt());
        return entity;
    }

    private AgentWorkflowRunModel toModel(AgentWorkflowRunEntity entity) {
        return new AgentWorkflowRunModel(entity.getRunId(), entity.getThreadId(), entity.getTurnId(),
                entity.getUserId(), AgentWorkflowTypeEnum.valueOf(entity.getWorkflowType()),
                AgentWorkflowStatusEnum.valueOf(entity.getStatus()),
                entity.getVersionNo() == null ? 0 : entity.getVersionNo(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
