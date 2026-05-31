package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRunRepository;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentPlanStepVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.AgentStepRunStatusEnumVO;
import cn.ethan.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Flow Plan 节点支撑类
 */
public abstract class AbstractExecuteSupport extends AbstractMultiThreadStrategyRouter<ExecuteCommandEntity, AgentExecutionContextVO, String> {

    private final Logger log = LoggerFactory.getLogger(AbstractExecuteSupport.class);

    @Resource
    protected IAgentRepository repository;

    @Resource
    protected IAgentRunRepository agentRunRepository;

    @Resource
    protected IAgentModelPort agentModelPort;

    @Override
    protected void multiThread(ExecuteCommandEntity requestParameter, AgentExecutionContextVO executionContext) throws ExecutionException, InterruptedException, TimeoutException {

    }

    protected AgentRunAggregate currentRun(AgentExecutionContextVO executionContext) {
        return executionContext.getAgentRunAggregate();
    }

    protected void sendStreamResult(AgentExecutionContextVO executionContext,
                                    AgentExecuteResultEntity result) {
        try {
            IAgentStreamPort streamPort = executionContext.getStreamPort();
            if (streamPort != null) {
                streamPort.send(result);
            }
        } catch (Exception e) {
            log.error("发送流式结果失败：{}", e.getMessage(), e);
        }
    }

    protected void sendCompleteResult(AgentExecutionContextVO executionContext) {
        AgentRunAggregate run = currentRun(executionContext);
        String sessionId = executionContext.currentSessionId();
        String runId = run == null ? null : run.runId();
        sendStreamResult(executionContext, AgentExecuteResultEntity.createCompleteResult(sessionId, runId));
    }

    protected void sendErrorResult(AgentExecutionContextVO executionContext, String message) {
        AgentRunAggregate run = currentRun(executionContext);
        String sessionId = executionContext.currentSessionId();
        String runId = run == null ? null : run.runId();
        sendStreamResult(executionContext, AgentExecuteResultEntity.createErrorResult(message, sessionId, runId));
    }

    protected int nextStreamStep(AgentExecutionContextVO executionContext) {
        return executionContext.nextStreamStepCursor();
    }

    protected long markStepRunning(AgentExecutionContextVO executionContext,
                                   String stepId,
                                   String stepName,
                                   Integer stepOrder,
                                   String stepType) {
        AgentRunAggregate run = currentRun(executionContext);
        long start = System.currentTimeMillis();
        agentRunRepository.createStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(stepId)
                .stepName(stepName)
                .stepOrder(stepOrder)
                .stepType(stepType)
                .status(AgentStepRunStatusEnumVO.RUNNING)
                .startTime(LocalDateTime.now())
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
        return start;
    }

    protected void markStepSuccess(AgentExecutionContextVO executionContext,
                                   String stepId,
                                   String summary,
                                   long startTime) {
        long end = System.currentTimeMillis();
        AgentRunAggregate run = currentRun(executionContext);
        agentRunRepository.updateStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(stepId)
                .status(AgentStepRunStatusEnumVO.SUCCESS)
                .outputSummary(limit(summary, 500))
                .costMillis(end - startTime)
                .endTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
    }

    protected void markStepFailed(AgentExecutionContextVO executionContext,
                                  String stepId,
                                  String errorMessage,
                                  long startTime) {
        long end = System.currentTimeMillis();
        AgentRunAggregate run = currentRun(executionContext);
        agentRunRepository.updateStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(stepId)
                .status(AgentStepRunStatusEnumVO.FAILED)
                .errorMessage(limit(errorMessage, 500))
                .costMillis(end - startTime)
                .endTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build());
    }

    protected void markPlannedStepTerminal(AgentExecutionContextVO executionContext,
                                           AgentPlanStepVO step,
                                           Integer stepOrder,
                                           AgentStepRunStatusEnumVO status,
                                           String reason) {
        if (step == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentRunAggregate run = currentRun(executionContext);
        agentRunRepository.createStep(AgentStepRunRecordVO.builder()
                .runId(run.runId())
                .stepId(step.getStepId())
                .stepName(step.getName())
                .stepOrder(stepOrder)
                .stepType(step.getType())
                .status(status)
                .outputSummary(limit(reason, 500))
                .costMillis(0L)
                .startTime(now)
                .endTime(now)
                .createTime(now)
                .updateTime(now)
                .build());
    }

    protected boolean stopIfCancelled(AgentExecutionContextVO executionContext, String message) {
        AgentRunAggregate run = currentRun(executionContext);
        if (run == null || executionContext.isCancelled()) {
            return executionContext.isCancelled();
        }
        if (!agentRunRepository.isCancelled(run.runId())) {
            return false;
        }
        executionContext.setCancelled(true);
        run.markCancelled(message);
        agentRunRepository.updateRun(run.toRecord());
        sendStreamResult(executionContext, AgentExecuteResultEntity.createSummarySubResult(
                "cancelled",
                message,
                executionContext.currentSessionId(),
                run.runId()
        ));
        sendStreamResult(executionContext, AgentExecuteResultEntity.createCompleteResult(
                message,
                executionContext.currentSessionId(),
                run.runId()
        ));
        return true;
    }

    protected void syncRunState(AgentExecutionContextVO executionContext) {
        AgentRunAggregate run = currentRun(executionContext);
        if (run != null) {
            agentRunRepository.updateRun(run.toRecord());
        }
    }

    protected String limit(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        return content.length() <= maxLength ? content : content.substring(0, maxLength) + "...";
    }

}
