package cn.ethan.ai.domain.agent.adapter.port;

import cn.ethan.ai.domain.agent.model.entity.AgentExecuteResultEntity;

/**
 * Agent 流式输出端口，隔离 Web 层具体传输实现。
 */
public interface IAgentStreamPort {

    void send(AgentExecuteResultEntity result);

    void complete();

    void onTimeout(Runnable callback);

    void onCompletion(Runnable callback);

}
