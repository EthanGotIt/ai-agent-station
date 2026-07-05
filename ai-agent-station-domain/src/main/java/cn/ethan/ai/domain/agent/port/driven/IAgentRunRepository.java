package cn.ethan.ai.domain.agent.port.driven;

import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRecord;
import cn.ethan.ai.domain.agent.model.valobj.AgentTurnRecord;

/**
 * 运行态仓储
 */
public interface IAgentRunRepository {

    void createTurn(AgentTurnRecord record);

    void completeTurn(AgentTurnRecord record);

    void createRun(AgentRunRecord record);

    void updateRun(AgentRunRecord record);

    void createStep(AgentStepRecord record);

    int nextAttemptNo(String turnId);

}
