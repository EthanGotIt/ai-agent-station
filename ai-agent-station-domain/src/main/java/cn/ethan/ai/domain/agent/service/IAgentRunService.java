package cn.ethan.ai.domain.agent.service;

import cn.ethan.ai.domain.agent.model.valobj.AgentRunDetailVO;

public interface IAgentRunService {

    AgentRunDetailVO queryRun(String runId);

    boolean cancelRun(String runId, String reason);

}
