package cn.ethan.ai.domain.agent.service.armory.business.data;

import cn.ethan.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.ethan.ai.domain.agent.service.armory.factory.DefaultArmoryStrategyFactory;

/**
 * 数据加载策略
 */
public interface ILoadDataStrategy {

    void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultArmoryStrategyFactory.DynamicContext dynamicContext);

}
