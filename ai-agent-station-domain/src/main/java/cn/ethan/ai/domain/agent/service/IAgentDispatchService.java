package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.adapter.port.IAgentStreamPort;
import cn.ethan.ai.domain.agent.model.entity.ExecuteCommandEntity;

/**
 * Agent 运行调度接口
 */
public interface IAgentDispatchService {

    void dispatch(ExecuteCommandEntity requestParameter, IAgentStreamPort streamPort) throws Exception;

}
