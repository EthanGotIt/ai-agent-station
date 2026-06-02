package cn.ethan.ai.infrastructure.adapter.repository;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunDetailVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunLifecycleVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentRunStatusEnumVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.ai.infrastructure.dao.IAiAgentRunDao;
import cn.ethan.ai.infrastructure.dao.IAiAgentStepRunDao;
import cn.ethan.ai.infrastructure.dao.po.AiAgentRun;
import cn.ethan.ai.infrastructure.dao.po.AiAgentStepRun;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 运行态仓储实现
 */
@Repository
public class AgentRunRepository implements IAgentRunRepository {

    @Resource
    private IAiAgentRunDao aiAgentRunDao;

    @Resource
    private IAiAgentStepRunDao aiAgentStepRunDao;

    @Override
    public void createRun(AgentRunRecordVO record) {
        aiAgentRunDao.insert(AiAgentRun.builder()
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
    public void updateRun(AgentRunRecordVO record) {
        aiAgentRunDao.updateByRunId(AiAgentRun.builder()
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
    public void createStep(AgentStepRunRecordVO record) {
        aiAgentStepRunDao.insert(AiAgentStepRun.builder()
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
    public void updateStep(AgentStepRunRecordVO record) {
        aiAgentStepRunDao.updateByRunIdAndStepId(AiAgentStepRun.builder()
                .runId(record.getRunId())
                .stepId(record.getStepId())
                .status(nameOf(record.getStatus()))
                .outputSummary(record.getOutputSummary())
                .errorMessage(record.getErrorMessage())
                .costMillis(record.getCostMillis())
                .endTime(record.getEndTime())
                .updateTime(record.getUpdateTime() == null ? LocalDateTime.now() : record.getUpdateTime())
                .build());
    }

    @Override
    public AgentRunDetailVO queryRunDetail(String runId) {
        if (StringUtils.isBlank(runId)) {
            return null;
        }
        AiAgentRun run = aiAgentRunDao.queryByRunId(runId);
        if (run == null) {
            return null;
        }
        List<AiAgentStepRun> stepRuns = aiAgentStepRunDao.queryByRunId(runId);
        List<AgentStepRunRecordVO> stepVos = toStepVos(stepRuns);
        AgentRunStatusEnumVO status = run.getStatus() == null ? AgentRunStatusEnumVO.INIT : AgentRunStatusEnumVO.valueOf(run.getStatus());
        return AgentRunDetailVO.builder()
                .runId(run.getRunId())
                .agentId(run.getAgentId())
                .sessionId(run.getSessionId())
                .userMessage(run.getUserMessage())
                .status(status)
                .finalSummary(run.getFinalSummary())
                .errorMessage(run.getErrorMessage())
                .cancelReason(run.getCancelReason())
                .startTime(run.getStartTime())
                .endTime(run.getEndTime())
                .createTime(run.getCreateTime())
                .updateTime(run.getUpdateTime())
                .lifecycle(AgentRunLifecycleVO.from(
                        status,
                        run.getErrorMessage(),
                        run.getCancelReason(),
                        null,
                        null,
                        null,
                        stepVos
                ))
                .steps(stepVos)
                .build();
    }

    @Override
    @Transactional
    public boolean cancelRun(String runId, String reason) {
        String cancelReason = StringUtils.defaultIfBlank(reason, "用户主动取消");
        LocalDateTime updateTime = LocalDateTime.now();
        boolean cancelled = aiAgentRunDao.cancelByRunId(runId, cancelReason, updateTime) > 0;
        if (cancelled) {
            aiAgentStepRunDao.cancelRunningByRunId(runId, cancelReason, updateTime);
        }
        return cancelled;
    }

    @Override
    public boolean isCancelled(String runId) {
        AiAgentRun run = aiAgentRunDao.queryByRunId(runId);
        return run != null && AgentRunStatusEnumVO.CANCELLED.name().equalsIgnoreCase(run.getStatus());
    }

    private List<AgentStepRunRecordVO> toStepVos(List<AiAgentStepRun> stepRuns) {
        if (stepRuns == null || stepRuns.isEmpty()) {
            return Collections.emptyList();
        }
        return stepRuns.stream()
                .map(item -> AgentStepRunRecordVO.builder()
                        .runId(item.getRunId())
                        .stepId(item.getStepId())
                        .stepName(item.getStepName())
                        .stepOrder(item.getStepOrder())
                        .stepType(item.getStepType())
                        .status(item.getStatus() == null ? AgentStepRunStatusEnumVO.PENDING : AgentStepRunStatusEnumVO.valueOf(item.getStatus()))
                        .outputSummary(item.getOutputSummary())
                        .errorMessage(item.getErrorMessage())
                        .costMillis(item.getCostMillis())
                        .startTime(item.getStartTime())
                        .endTime(item.getEndTime())
                        .createTime(item.getCreateTime())
                        .updateTime(item.getUpdateTime())
                        .build())
                .toList();
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

}
