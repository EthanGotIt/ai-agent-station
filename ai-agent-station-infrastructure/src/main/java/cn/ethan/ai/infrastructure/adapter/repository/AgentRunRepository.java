package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.port.driven.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.infrastructure.dao.AgentRunMapper;
import cn.ethan.ai.infrastructure.dao.AgentStepMapper;
import cn.ethan.ai.infrastructure.dao.AgentTurnMapper;
import cn.ethan.ai.infrastructure.dao.po.AgentRunPO;
import cn.ethan.ai.infrastructure.dao.po.AgentStepPO;
import cn.ethan.ai.infrastructure.dao.po.AgentTurnPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 运行态仓储实现
 */
@Repository
public class AgentRunRepository implements IAgentRunRepository {

    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final AgentTurnMapper agentTurnMapper;

    public AgentRunRepository(AgentRunMapper agentRunMapper,
                              AgentStepMapper agentStepMapper,
                              AgentTurnMapper agentTurnMapper) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepMapper = agentStepMapper;
        this.agentTurnMapper = agentTurnMapper;
    }

    @Override
    public void createTurn(AgentTurnRecord record) {
        agentTurnMapper.insert(toTurnPO(record));
    }

    @Override
    public void completeTurn(AgentTurnRecord record) {
        agentTurnMapper.updateByTurnId(toTurnPO(record));
    }

    @Override
    public void createRun(AgentRunRecord record) {
        agentRunMapper.insert(AgentRunPO.builder()
                .runId(record.getRunId())
                .turnId(record.getTurnId())
                .caseId(record.getCaseId())
                .agentId(record.getAgentId())
                .triggerType(record.getTriggerType())
                .attemptNo(record.getAttemptNo())
                .status(nameOf(record.getStatus()))
                .finalSummary(record.getFinalSummary())
                .errorMessage(record.getErrorMessage())
                .cancelReason(record.getCancelReason())
                .checkpointBefore(record.getCheckpointBefore())
                .checkpointAfter(record.getCheckpointAfter())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
    }

    @Override
    public void updateRun(AgentRunRecord record) {
        agentRunMapper.updateByRunId(AgentRunPO.builder()
                .runId(record.getRunId())
                .status(nameOf(record.getStatus()))
                .finalSummary(record.getFinalSummary())
                .errorMessage(record.getErrorMessage())
                .cancelReason(record.getCancelReason())
                .checkpointAfter(record.getCheckpointAfter())
                .endTime(record.getEndTime())
                .updateTime(LocalDateTime.now())
                .build());
    }

    @Override
    public void createStep(AgentStepRecord record) {
        agentStepMapper.insert(AgentStepPO.builder()
                .runId(record.getRunId())
                .stepId(record.getStepId())
                .stepName(record.getStepName())
                .stepOrder(record.getStepOrder())
                .stepType(record.getStepType())
                .status(nameOf(record.getStatus()))
                .outputSummary(record.getOutputSummary())
                .errorMessage(record.getErrorMessage())
                .costMillis(record.getCostMillis())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .createTime(record.getCreateTime() == null ? LocalDateTime.now() : record.getCreateTime())
                .updateTime(record.getUpdateTime() == null ? LocalDateTime.now() : record.getUpdateTime())
                .build());
    }

    @Override
    public int nextAttemptNo(String turnId) {
        return agentRunMapper.countByTurnId(turnId) + 1;
    }

    private AgentTurnPO toTurnPO(AgentTurnRecord record) {
        return AgentTurnPO.builder()
                .turnId(record.getTurnId())
                .caseId(record.getCaseId())
                .sessionId(record.getSessionId())
                .actorId(record.getActorId())
                .turnType(record.getTurnType())
                .inputSummary(record.getInputSummary())
                .outputSummary(record.getOutputSummary())
                .status(record.getStatus())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .createTime(record.getCreateTime() == null ? LocalDateTime.now() : record.getCreateTime())
                .updateTime(record.getUpdateTime() == null ? LocalDateTime.now() : record.getUpdateTime())
                .build();
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

}
