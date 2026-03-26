package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.IExecuteStrategy;
import cn.ethan.ai.domain.agent.service.execute.flow.step.factory.DefaultFlowAgentExecuteStrategyFactory;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * 配置过程处理
 */
@Slf4j
@Service("flowAgentExecuteStrategy")
public class FlowAgentExecuteStrategy implements IExecuteStrategy {

    @Resource
    private DefaultFlowAgentExecuteStrategyFactory defaultFlowAgentExecuteStrategyFactory;

    @Override
    public void execute(ExecuteCommandEntity executeCommandEntity, ResponseBodyEmitter emitter) throws Exception {
        StrategyHandler<ExecuteCommandEntity, DefaultFlowAgentExecuteStrategyFactory.DynamicContext, String> executeHandler
                = defaultFlowAgentExecuteStrategyFactory.armoryStrategyHandler();

        DefaultFlowAgentExecuteStrategyFactory.DynamicContext dynamicContext = new DefaultFlowAgentExecuteStrategyFactory.DynamicContext();
        dynamicContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : 4);
        dynamicContext.setExecutionHistory(new StringBuilder());
        dynamicContext.setCurrentTask(executeCommandEntity.getMessage());
        dynamicContext.setStreamProtocol(StreamTransportTypeEnumVO.fromCode(executeCommandEntity.getStreamProtocol()));
        dynamicContext.setValue("emitter", emitter);
        dynamicContext.setValue("sessionId", executeCommandEntity.getSessionId());

        String apply = executeHandler.apply(executeCommandEntity, dynamicContext);
        log.info("流程执行结果:{}", apply);
    }

}
