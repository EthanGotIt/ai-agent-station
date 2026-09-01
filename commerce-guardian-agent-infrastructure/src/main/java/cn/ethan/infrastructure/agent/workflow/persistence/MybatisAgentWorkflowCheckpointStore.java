package cn.ethan.infrastructure.agent.workflow.persistence;

import cn.ethan.core.agent.thread.AgentInteractionTypeEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointModel;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStatusEnum;
import cn.ethan.core.agent.workflow.AgentWorkflowCheckpointStore;
import cn.ethan.core.agent.workflow.AgentWorkflowDecisionEnum;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadEntity;
import cn.ethan.infrastructure.agent.thread.persistence.AgentThreadMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 类型职责：以 Thread 互斥引用、事实指纹和版本 CAS 持久化 Workflow Checkpoint。
 *
 * @author ethan
 * @date 2026-08-27
 */
@Repository
public class MybatisAgentWorkflowCheckpointStore implements AgentWorkflowCheckpointStore {

    private final AgentWorkflowCheckpointMapper mapper;
    private final AgentThreadMapper threadMapper;
    private final Clock clock;

    @Autowired
    public MybatisAgentWorkflowCheckpointStore(AgentWorkflowCheckpointMapper mapper,
                                                AgentThreadMapper threadMapper) {
        this(mapper, threadMapper, Clock.systemUTC());
    }

    MybatisAgentWorkflowCheckpointStore(AgentWorkflowCheckpointMapper mapper,
                                        AgentThreadMapper threadMapper,
                                        Clock clock) {
        this.mapper = mapper;
        this.threadMapper = threadMapper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Optional<AgentWorkflowCheckpointModel> find(String userId, String checkpointId) {
        AgentWorkflowCheckpointEntity entity = mapper.selectById(checkpointId);
        return entity == null || !userId.equals(entity.getUserId())
                ? Optional.empty() : Optional.of(toModel(entity));
    }

    @Override
    public Optional<AgentWorkflowCheckpointModel> findOpen(String userId, String threadId) {
        return Optional.ofNullable(mapper.selectOpen(userId, threadId)).map(this::toModel);
    }

    @Override
    @Transactional
    public void create(AgentWorkflowCheckpointModel checkpoint) {
        if (checkpoint.status() != AgentWorkflowCheckpointStatusEnum.OPEN || checkpoint.version() != 0
                || checkpoint.decision() != null || checkpoint.decidedAt() != null) {
            throw new IllegalArgumentException("create 只接受 OPEN/v0 Checkpoint");
        }
        AgentThreadEntity thread = threadMapper.selectForUpdate(checkpoint.threadId());
        if (thread == null || !checkpoint.userId().equals(thread.getUserId())) {
            throw new IllegalStateException("Workflow Checkpoint 所属 Thread 不存在");
        }
        if (thread.getOpenInteractionId() != null || thread.getOpenQuestionId() != null
                || mapper.selectOpen(checkpoint.userId(), checkpoint.threadId()) != null) {
            throw new IllegalStateException("同一 Thread 只能存在一个开放交互");
        }
        mapper.insert(toEntity(checkpoint));
        if (threadMapper.setOpenInteraction(checkpoint.threadId(), checkpoint.userId(),
                AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name(), checkpoint.checkpointId(), checkpoint.createdAt()) != 1) {
            throw new IllegalStateException("Workflow Checkpoint 开放指针已被其他事务占用");
        }
    }

    @Override
    @Transactional
    public boolean decide(String userId, String checkpointId, long expectedVersion,
                          AgentWorkflowDecisionEnum decision, String currentFactsFingerprint) {
        if (decision == null || expectedVersion < 0) {
            return false;
        }
        AgentWorkflowCheckpointEntity checkpoint = mapper.selectById(checkpointId);
        if (checkpoint == null || !userId.equals(checkpoint.getUserId())) {
            return false;
        }
        AgentThreadEntity thread = threadMapper.selectForUpdate(checkpoint.getThreadId());
        if (thread == null
                || !checkpointId.equals(thread.getOpenInteractionId())
                || !AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name().equals(thread.getOpenInteractionType())) {
            return false;
        }
        Instant now = clock.instant();
        if (decision != AgentWorkflowDecisionEnum.REJECT
                && (currentFactsFingerprint == null
                || !currentFactsFingerprint.equals(checkpoint.getFactsFingerprint()))) {
            int superseded = mapper.update(null, new UpdateWrapper<AgentWorkflowCheckpointEntity>()
                    .eq("CHECKPOINT_ID", checkpointId).eq("USER_ID", userId)
                    .eq("VERSION_NO", expectedVersion).eq("STATUS", AgentWorkflowCheckpointStatusEnum.OPEN.name())
                    .set("STATUS", AgentWorkflowCheckpointStatusEnum.SUPERSEDED.name())
                    .set("DECIDED_AT", now).set("VERSION_NO", expectedVersion + 1));
            if (superseded == 1) {
                if (threadMapper.clearOpenInteraction(checkpoint.getThreadId(), userId,
                        AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name(), checkpointId, now) != 1) {
                    throw new IllegalStateException("Workflow Checkpoint 已失效但 Thread 开放指针未清理");
                }
            }
            return false;
        }
        AgentWorkflowCheckpointStatusEnum status = decision == AgentWorkflowDecisionEnum.APPROVE
                ? AgentWorkflowCheckpointStatusEnum.APPROVED : AgentWorkflowCheckpointStatusEnum.REJECTED;
        int updated = mapper.update(null, new UpdateWrapper<AgentWorkflowCheckpointEntity>()
                .eq("CHECKPOINT_ID", checkpointId).eq("USER_ID", userId)
                .eq("VERSION_NO", expectedVersion).eq("STATUS", AgentWorkflowCheckpointStatusEnum.OPEN.name())
                .set("STATUS", status.name()).set("DECISION", decision.name())
                .set("DECIDED_AT", now).set("VERSION_NO", expectedVersion + 1));
        if (updated != 1) {
            return false;
        }
        if (threadMapper.clearOpenInteraction(checkpoint.getThreadId(), userId,
                AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name(), checkpointId, now) != 1) {
            throw new IllegalStateException("Workflow Checkpoint 已决策但 Thread 开放指针未清理");
        }
        return true;
    }

    @Override
    @Transactional
    public boolean supersede(String userId, String checkpointId, long expectedVersion) {
        AgentWorkflowCheckpointEntity checkpoint = mapper.selectById(checkpointId);
        if (checkpoint == null || !userId.equals(checkpoint.getUserId())) {
            return false;
        }
        Instant now = clock.instant();
        int updated = mapper.update(null, new UpdateWrapper<AgentWorkflowCheckpointEntity>()
                .eq("CHECKPOINT_ID", checkpointId).eq("USER_ID", userId)
                .eq("VERSION_NO", expectedVersion)
                .in("STATUS", AgentWorkflowCheckpointStatusEnum.OPEN.name(),
                        AgentWorkflowCheckpointStatusEnum.APPROVED.name())
                .set("STATUS", AgentWorkflowCheckpointStatusEnum.SUPERSEDED.name())
                .setSql("DECISION = NULL")
                .set("DECIDED_AT", now).set("VERSION_NO", expectedVersion + 1));
        if (updated == 1) {
            if (AgentWorkflowCheckpointStatusEnum.OPEN.name().equals(checkpoint.getStatus())) {
                if (threadMapper.clearOpenInteraction(checkpoint.getThreadId(), userId,
                        AgentInteractionTypeEnum.WORKFLOW_CHECKPOINT.name(), checkpointId, now) != 1) {
                    throw new IllegalStateException("Workflow Checkpoint 已失效但 Thread 开放指针未清理");
                }
            }
        }
        return updated == 1;
    }

    private AgentWorkflowCheckpointEntity toEntity(AgentWorkflowCheckpointModel model) {
        AgentWorkflowCheckpointEntity entity = new AgentWorkflowCheckpointEntity();
        entity.setCheckpointId(model.checkpointId());
        entity.setRunId(model.runId());
        entity.setThreadId(model.threadId());
        entity.setTurnId(model.turnId());
        entity.setUserId(model.userId());
        entity.setNodeId(model.nodeId());
        entity.setActionType(model.actionType());
        entity.setOrderId(model.orderId());
        entity.setImpactSummary(model.impactSummary());
        entity.setFactsFingerprint(model.factsFingerprint());
        entity.setVersionNo(model.version());
        entity.setStatus(model.status().name());
        entity.setDecision(model.decision() == null ? null : model.decision().name());
        entity.setCreatedAt(model.createdAt());
        entity.setDecidedAt(model.decidedAt());
        return entity;
    }

    private AgentWorkflowCheckpointModel toModel(AgentWorkflowCheckpointEntity entity) {
        return new AgentWorkflowCheckpointModel(entity.getCheckpointId(), entity.getRunId(), entity.getThreadId(),
                entity.getTurnId(), entity.getUserId(), entity.getNodeId(), entity.getActionType(), entity.getOrderId(),
                entity.getImpactSummary(), entity.getFactsFingerprint(), value(entity.getVersionNo()),
                AgentWorkflowCheckpointStatusEnum.valueOf(entity.getStatus()), parseDecision(entity.getDecision()),
                entity.getCreatedAt(), entity.getDecidedAt());
    }

    private AgentWorkflowDecisionEnum parseDecision(String value) {
        return value == null || value.isBlank() ? null : AgentWorkflowDecisionEnum.valueOf(value);
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }
}
