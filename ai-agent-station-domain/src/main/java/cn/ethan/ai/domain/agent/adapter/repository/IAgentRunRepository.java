package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecord;

/**
 * 运行态仓储
 */
public interface IAgentRunRepository {

    void createRun(AgentRunRecord record);

    void updateRun(AgentRunRecord record);

    void createStep(AgentStepRunRecord record);

    boolean cancelRun(String runId, String reason);

}
