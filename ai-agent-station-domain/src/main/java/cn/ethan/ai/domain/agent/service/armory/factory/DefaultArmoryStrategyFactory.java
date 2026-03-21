package cn.ethan.ai.domain.agent.service.armory.factory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.service.armory.RootNode;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import lombok.*;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 工厂类
 */
@Service
public class DefaultArmoryStrategyFactory {

    private final RootNode rootNode;

    public DefaultArmoryStrategyFactory(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public StrategyHandler<ArmoryCommandEntity, DynamicContext, String> armoryStrategyHandler(){
        return rootNode;
    }

    @EqualsAndHashCode(callSuper = true)
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DynamicContext extends cn.ethan.wrench.design.framework.tree.DynamicContext {

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
