package cn.ethan.infrastructure.agent.thread.persistence;

import cn.ethan.core.agent.execution.AgentTurnResumeSignalModel;
import cn.ethan.core.agent.execution.AgentTurnResumeSignalStore;
import cn.ethan.core.agent.execution.AgentTurnResumeSignalModel.SignalKindEnum;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 类型职责：通过数据库唯一键和版本 CAS 去重、收敛 Turn 恢复信号。
 *
 * @author ethan
 * @date 2026-09-29
 */
@Repository
public class MybatisAgentTurnResumeSignalStore implements AgentTurnResumeSignalStore {

    private final AgentTurnResumeSignalMapper mapper;
    private final JacksonAgentQuestionAnswerCodec questionAnswerCodec;
    private final JacksonAgentWorkflowDecisionCodec decisionCodec;

    public MybatisAgentTurnResumeSignalStore(
            AgentTurnResumeSignalMapper mapper,
            JacksonAgentQuestionAnswerCodec questionAnswerCodec,
            JacksonAgentWorkflowDecisionCodec decisionCodec
    ) {
        this.mapper = mapper;
        this.questionAnswerCodec = questionAnswerCodec;
        this.decisionCodec = decisionCodec;
    }

    @Override
    public Optional<AgentTurnResumeSignalModel> findByRequest(String userId, String requestId) {
        return Optional.ofNullable(mapper.selectByRequest(userId, requestId)).map(this::toModel);
    }

    @Override
    public Optional<AgentTurnResumeSignalModel> findPending(String userId, String turnId) {
        return Optional.ofNullable(mapper.selectPending(userId, turnId)).map(this::toModel);
    }

    @Override
    public void create(AgentTurnResumeSignalModel signal) {
        mapper.insert(toEntity(signal));
    }

    @Override
    public boolean markApplied(AgentTurnResumeSignalModel expected, AgentTurnResumeSignalModel next) {
        if (!expected.signalId().equals(next.signalId()) || !expected.turnId().equals(next.turnId())
                || next.version() != expected.version() + 1
                || next.status() != AgentTurnResumeSignalModel.SignalStatusEnum.APPLIED) {
            throw new IllegalArgumentException("恢复信号 CAS 身份、状态或版本不连续");
        }
        int updated = mapper.update(null, new UpdateWrapper<AgentTurnResumeSignalEntity>()
                .eq("SIGNAL_ID", expected.signalId())
                .eq("USER_ID", expected.userId())
                .eq("STATUS", AgentTurnResumeSignalModel.SignalStatusEnum.PENDING.name())
                .eq("VERSION_NO", expected.version())
                .set("STATUS", next.status().name())
                .set("APPLIED_AT", next.appliedAt())
                .set("VERSION_NO", next.version()));
        if (updated > 1) {
            throw new IllegalStateException("恢复信号 CAS 更新了多行：" + expected.signalId());
        }
        return updated == 1;
    }

    private AgentTurnResumeSignalEntity toEntity(AgentTurnResumeSignalModel model) {
        AgentTurnResumeSignalEntity entity = new AgentTurnResumeSignalEntity();
        entity.setSignalId(model.signalId());
        entity.setTurnId(model.turnId());
        entity.setUserId(model.userId());
        entity.setRequestId(model.requestId());
        entity.setSignalKind(model.kind().name());
        entity.setInteractionId(model.interactionId());
        entity.setExpectedInteractionVersion(model.expectedInteractionVersion());
        entity.setPayloadJson(switch (model.kind()) {
            case QUESTION_ANSWER -> questionAnswerCodec.encode(model.questionAnswerInput());
            case WORKFLOW_DECISION -> decisionCodec.encode(model.workflowDecisionInput());
            case STEER, COMMAND_RESULT -> model.payloadJson();
        });
        entity.setItemId(model.itemId());
        entity.setStatus(model.status().name());
        entity.setVersionNo(model.version());
        entity.setCreatedAt(model.createdAt());
        entity.setAppliedAt(model.appliedAt());
        return entity;
    }

    private AgentTurnResumeSignalModel toModel(AgentTurnResumeSignalEntity entity) {
        SignalKindEnum kind = SignalKindEnum.valueOf(entity.getSignalKind());
        return new AgentTurnResumeSignalModel(entity.getSignalId(), entity.getTurnId(), entity.getUserId(),
                entity.getRequestId(), kind, entity.getInteractionId(), value(entity.getExpectedInteractionVersion()),
                kind == SignalKindEnum.QUESTION_ANSWER ? questionAnswerCodec.decode(entity.getPayloadJson()) : null,
                kind == SignalKindEnum.WORKFLOW_DECISION ? decisionCodec.decode(entity.getPayloadJson()) : null,
                kind == SignalKindEnum.STEER || kind == SignalKindEnum.COMMAND_RESULT ? entity.getPayloadJson() : null,
                entity.getItemId(), AgentTurnResumeSignalModel.SignalStatusEnum.valueOf(entity.getStatus()),
                value(entity.getVersionNo()), entity.getCreatedAt(), entity.getAppliedAt());
    }

    private static long value(Long value) { return value == null ? 0L : value; }
}
