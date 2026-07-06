package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;
import cn.ethan.ai.domain.agent.port.driven.IAgentTurnRepository;
import cn.ethan.ai.infrastructure.dao.AgentTurnMapper;
import cn.ethan.ai.infrastructure.dao.po.AgentTurnPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * Turn 运行态仓储实现。
 */
@Repository
public class AgentTurnRepository implements IAgentTurnRepository {

    private final AgentTurnMapper agentTurnMapper;

    public AgentTurnRepository(AgentTurnMapper agentTurnMapper) {
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
    public int nextAttemptNo(String caseId) {
        return agentTurnMapper.countByCaseId(caseId) + 1;
    }

    private AgentTurnPO toTurnPO(AgentTurnRecord record) {
        return AgentTurnPO.builder()
                .turnId(record.getTurnId())
                .caseId(record.getCaseId())
                .sessionId(record.getSessionId())
                .actorId(record.getActorId())
                .turnType(record.getTurnType())
                .attemptNo(record.getAttemptNo())
                .inputSummary(record.getInputSummary())
                .outputSummary(record.getOutputSummary())
                .status(record.getStatus())
                .checkpointBefore(record.getCheckpointBefore())
                .checkpointAfter(record.getCheckpointAfter())
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .createTime(record.getCreateTime() == null ? LocalDateTime.now() : record.getCreateTime())
                .updateTime(record.getUpdateTime() == null ? LocalDateTime.now() : record.getUpdateTime())
                .build();
    }
}
