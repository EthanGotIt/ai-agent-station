package cn.ethan.ai.domain.agent.adapter.repository;

import cn.ethan.ai.domain.agent.model.valobj.AgentRunDetailVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentRunRecordVO;
import cn.ethan.ai.domain.agent.model.valobj.AgentStepRunRecordVO;

/**
 * 运行态仓储
 */
public interface IAgentRunRepository {

    void createRun(AgentRunRecordVO record);

    void updateRun(AgentRunRecordVO record);

    void createStep(AgentStepRunRecordVO record);

    void updateStep(AgentStepRunRecordVO record);

    AgentRunDetailVO queryRunDetail(String runId);

    boolean cancelRun(String runId, String reason);

    boolean isCancelled(String runId);

}
