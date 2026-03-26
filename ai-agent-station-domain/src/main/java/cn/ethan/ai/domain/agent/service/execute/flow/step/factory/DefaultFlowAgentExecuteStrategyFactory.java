package cn.ethan.ai.domain.agent.service.execute.flow.step.factory;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.AiAgentClientFlowConfigVO;
import cn.ethan.ai.domain.agent.model.valobj.enums.StreamTransportTypeEnumVO;
import cn.ethan.ai.domain.agent.service.execute.flow.step.RootNode;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import lombok.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 流程执行策略工厂类
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2025/8/24 14:28
 */
@Service
public class DefaultFlowAgentExecuteStrategyFactory {

    private final RootNode flowRootNode;

    public DefaultFlowAgentExecuteStrategyFactory(RootNode flowRootNode) {
        this.flowRootNode = flowRootNode;
    }

    public StrategyHandler<ExecuteCommandEntity, DynamicContext, String> armoryStrategyHandler(){
        return flowRootNode;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext extends cn.ethan.wrench.design.framework.tree.DynamicContext {

        // 任务执行步骤
        private int step = 1;

        // 最大任务步骤
        private int maxStep = 4;

        private StringBuilder executionHistory;

        private String currentTask;

        boolean isCompleted = false;

        private StreamTransportTypeEnumVO streamProtocol = StreamTransportTypeEnumVO.SSE;

        private Map<String, AiAgentClientFlowConfigVO> aiAgentClientFlowConfigVOMap;

        private Map<String, Object> dataObjects = new HashMap<>();

        public <T> void setValue(String key, T value) {
            dataObjects.put(key, value);
        }

        @SuppressWarnings("unchecked")
        public <T> T getValue(String key) {
            return (T) dataObjects.get(key);
        }
    }

}
