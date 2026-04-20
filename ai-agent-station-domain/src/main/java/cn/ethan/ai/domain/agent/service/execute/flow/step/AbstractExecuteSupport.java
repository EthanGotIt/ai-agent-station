package cn.ethan.ai.domain.agent.service.execute.flow.step;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.adapter.port.IAgentModelPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.aggregate.AgentRunAggregate;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.wrench.design.framework.tree.AbstractMultiThreadStrategyRouter;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

}
