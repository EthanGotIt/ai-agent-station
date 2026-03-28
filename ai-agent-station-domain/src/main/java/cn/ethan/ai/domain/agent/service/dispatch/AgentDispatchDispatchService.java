package cn.ethan.ai.domain.agent.service.dispatch;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.entity.AutoAgentExecuteResultEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentVO;
import cn.ethan.ai.domain.agent.service.IAgentDispatchService;
import cn.ethan.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.ethan.ai.types.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson.JSON;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 服务接口
 */
@Slf4j
@Service
public class AgentDispatchDispatchService implements IAgentDispatchService {

    @Resource
    private Map<String, IExecuteStrategy> executeStrategyMap;

    @Resource
    private IAgentRepository repository;

    @Resource
    private ThreadPoolExecutor threadPoolExecutor;

    @Override
    public void dispatch(ExecuteCommandEntity requestParameter, ResponseBodyEmitter emitter) throws Exception {
        AiAgentVO aiAgentVO = repository.queryAiAgentByAgentId(requestParameter.getAiAgentId());
        if (aiAgentVO == null) {
            throw new BizException("不存在的智能体 aiAgentId:" + requestParameter.getAiAgentId());
        }

        String strategy = aiAgentVO.getStrategy();
        IExecuteStrategy executeStrategy = executeStrategyMap.get(strategy);
        if (null == executeStrategy) {
            throw new BizException("不存在的执行策略类型 strategy:" + strategy);
        }

        Future<?> future = threadPoolExecutor.submit(() -> {
            try {
                executeStrategy.execute(requestParameter, emitter);
            } catch (Exception e) {
                log.error("AutoAgent执行异常：{}", e.getMessage(), e);
                try {
                    AutoAgentExecuteResultEntity errorResult = AutoAgentExecuteResultEntity.createErrorResult(
                            "执行异常：" + (e.getMessage() == null ? "未知错误" : e.getMessage()),
                            requestParameter.getSessionId()
                    );
                    emitter.send(encodeStreamResult(errorResult));
                } catch (Exception ex) {
                    log.error("发送异常信息失败：{}", ex.getMessage(), ex);
                }
            } finally {
                completeEmitter(emitter);
            }
        });

        // 客户端断开/超时时：尽快取消后台任务（策略本身仍需支持 interrupt 才能做到完全停止）
        emitter.onTimeout(() -> {
            try {
                future.cancel(true);
            } finally {
                completeEmitter(emitter);
            }
        });

        emitter.onCompletion(() -> {
            future.cancel(true);
        });

    }

    private String encodeStreamResult(AutoAgentExecuteResultEntity result) {
        String json = JSON.toJSONString(result);
        return json + "\n";
    }

    private void completeEmitter(ResponseBodyEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // ignore重复 complete 或连接已关闭的异常
            log.debug("完成流式输出失败：{}", e.getMessage(), e);
        }
    }

}
