package cn.ethan.ai.domain.agent.service.dispatch;

import cn.ethan.ai.domain.agent.adapter.repository.IAgentRepository;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentVO;
import cn.ethan.ai.domain.agent.service.IAgentDispatchService;
import cn.ethan.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.ethan.ai.types.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.util.Map;
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

        String strategy = aiAgentVO.getStrategy();
        IExecuteStrategy executeStrategy = executeStrategyMap.get(strategy);
        if (null == executeStrategy) {
            throw new BizException("不存在的执行策略类型 strategy:" + strategy);
        }

        threadPoolExecutor.execute(() -> {
            try {
                executeStrategy.execute(requestParameter, emitter);
            } catch (Exception e) {
                log.error("AutoAgent执行异常：{}", e.getMessage(), e);
                try {
                    emitter.send("执行异常：" + e.getMessage());
                } catch (Exception ex) {
                    log.error("发送异常信息失败：{}", ex.getMessage(), ex);
                }
            } finally {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.error("完成流式输出失败：{}", e.getMessage(), e);
                }
            }
        });

    }

}
