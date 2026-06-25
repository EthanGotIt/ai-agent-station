package cn.ethan.ai.domain.agent.service.dispatch;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentVO;
import cn.ethan.ai.domain.agent.service.IAgentDispatchService;
import cn.ethan.ai.types.exception.AgentExecutionException;
import cn.ethan.ai.domain.agent.service.execute.springai.SpringAiAgentRuntime;
import cn.ethan.ai.types.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 服务调度
 */
@Slf4j
@Service
public class AgentDispatchService implements IAgentDispatchService {

    @Resource
    private SpringAiAgentRuntime springAiAgentRuntime;

    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    public void dispatch(ExecuteCommandEntity requestParameter, IAgentStreamPort streamPort) {
        AiAgentVO aiAgentVO = repository.queryAiAgentByAgentId(requestParameter.getAiAgentId());
        if (aiAgentVO == null || (aiAgentVO.getStatus() != null && aiAgentVO.getStatus() != 1)) {
            throw new BizException("智能体不存在或已禁用，aiAgentId:" + requestParameter.getAiAgentId());
        }

        Future<?> future = threadPoolExecutor.submit(() -> {
            try {
                springAiAgentRuntime.execute(requestParameter, streamPort);
            } catch (Exception e) {
                log.error("Agent 执行异常：{}", e.getMessage(), e);
                String runId = e instanceof AgentExecutionException executionException
                        ? executionException.getRunId()
                        : null;
                AgentExecuteResultEntity errorResult = AgentExecuteResultEntity.createErrorResult(
                        "执行异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                        requestParameter.getSessionId(),
                        runId
                );
                streamPort.send(errorResult);
            } finally {
                streamPort.complete();
            }
        });

        streamPort.onTimeout(() -> {
            try {
                future.cancel(true);
            } finally {
                streamPort.complete();
            }
        });

        streamPort.onCompletion(() -> {
            if (!future.isDone()) {
                future.cancel(true);
            }
        });
    }
}
