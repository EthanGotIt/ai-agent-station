package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecord;
import cn.ethan.ai.infrastructure.dao.AgentRunMapper;
import cn.ethan.ai.infrastructure.dao.AgentStepRunMapper;
import cn.ethan.ai.infrastructure.dao.po.AgentRunPO;
import cn.ethan.ai.infrastructure.dao.po.AgentStepRunPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/**
 * 运行态仓储实现
 */
@Repository
public class AgentRunRepository implements IAgentRunRepository {

    private final AgentRunMapper agentRunMapper;
    private final AgentStepRunMapper agentStepRunMapper;

    public AgentRunRepository(AgentRunMapper agentRunMapper, AgentStepRunMapper agentStepRunMapper) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepRunMapper = agentStepRunMapper;
    }

    @Override
    public void createRun(AgentRunRecord record) {
        agentRunMapper.insert(AgentRunPO.builder()
                .runId(record.getRunId())
                .agentId(record.getAgentId())
                .sessionId(record.getSessionId())
                .userMessage(record.getUserMessage())
                .status(nameOf(record.getStatus()))
                .finalSummary(record.getFinalSummary())
                .errorMessage(record.getErrorMessage())
                .cancelReason(record.getCancelReason())
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
                .startTime(record.getStartTime())
                .endTime(record.getEndTime())
                .updateTime(LocalDateTime.now())
                .build());
    }

    @Override
    public void createStep(AgentStepRunRecord record) {
        agentStepRunMapper.insert(AgentStepRunPO.builder()
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
    public boolean cancelRun(String runId, String reason) {
        String actualReason = reason == null || reason.isBlank() ? "用户主动取消" : reason;
        return agentRunMapper.cancelByRunId(runId, actualReason, LocalDateTime.now()) > 0;
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

}
