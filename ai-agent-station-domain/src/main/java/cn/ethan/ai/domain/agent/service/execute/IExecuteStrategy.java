package cn.ethan.ai.domain.agent.service.execute;

import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;

/**
 * 执行策略接口
 */
public interface IExecuteStrategy {

    void execute(ExecuteCommandEntity requestParameter) throws Exception;

}
