package cn.ethan.ai.domain.agent.service.execute.flow;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AgentExecutionContextVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.step.RootNode;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Flow Plan 执行服务
 */
@Slf4j
@Service
public class FlowPlanExecuteService {

    private static final int DEFAULT_MAX_STEP = 3;

    @Resource
    private RootNode flowRootNode;

    public void execute(ExecuteCommandEntity executeCommandEntity, IAgentStreamPort streamPort) throws Exception {
        StrategyHandler<ExecuteCommandEntity, AgentExecutionContextVO, String> executeHandler
                = flowRootNode;

        AgentExecutionContextVO executionContext = new AgentExecutionContextVO();
        executionContext.setMaxStep(executeCommandEntity.getMaxStep() != null ? executeCommandEntity.getMaxStep() : DEFAULT_MAX_STEP);
        executionContext.setStreamProtocol(StreamTransportTypeEnumVO.fromCode(executeCommandEntity.getStreamProtocol()));
        executionContext.setStreamPort(streamPort);
        executionContext.bindSessionId(executeCommandEntity.getSessionId());

        String apply = executeHandler.apply(executeCommandEntity, executionContext);
        log.info("Flow Plan 执行结果：{}", apply);
    }

}
