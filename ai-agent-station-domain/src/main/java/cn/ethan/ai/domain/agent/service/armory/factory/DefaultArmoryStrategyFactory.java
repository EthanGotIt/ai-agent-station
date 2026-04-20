package cn.ethan.ai.domain.agent.service.armory.factory;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.model.valobj.ArmoryAssemblyContextVO;
import cn.ethan.ai.domain.agent.service.armory.RootNode;
import cn.ethan.wrench.design.framework.tree.StrategyHandler;
import org.springframework.stereotype.Service;

/**
 * Armory 装配流程入口工厂。
 */
@Service
public class DefaultArmoryStrategyFactory {

    private final RootNode rootNode;

    public DefaultArmoryStrategyFactory(RootNode rootNode) {
        this.rootNode = rootNode;
    }

    public StrategyHandler<ArmoryCommandEntity, ArmoryAssemblyContextVO, String> armoryStrategyHandler() {
        return rootNode;
    }

}
